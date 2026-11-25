// Shows the engine's frames on an Android surface, and starts the engine. Built with the NDK (construire.sh).
// The engine connects to the socket, passes a memfd once (SCM_RIGHTS with a FrameHeader), then sends one u32 slot per frame.
// A vsync thread draws the newest frame every few screen refreshes and answers with one byte, which starts the next frame.
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <android/log.h>
#include <jni.h>
#include <signal.h>
#include <spawn.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define FRAME_MAGIC 0x3146424e
#define VK_FORMAT_B8G8R8A8_UNORM 44
#define VK_FORMAT_B8G8R8A8_SRGB 50
#define WINDOW_FORMAT_RGBA_8888 1
#define FRAME_RATE_FIXED_SOURCE 1
#define NS_PER_SECOND 1000000000LL
#define NS_PER_MS 1000000LL
#define MIN_PERIOD_NS (2 * NS_PER_MS)
#define VSYNCS_PER_MEASURE 60
#define MAX_GAP_VSYNCS 240
#define STATS_NS (10 * NS_PER_SECOND)
#define DISPLAY_NICE (-8)

struct FrameHeader {
    uint32_t magic, width, height, stride, format, slots;
};

typedef struct {
    int32_t width, height, stride, format;
    void* bits;
    void* reserved[7];
} WindowBuffer;

typedef struct {
    int32_t left, top, right, bottom;
} WindowRect;

static void* (*window_from_surface)(JNIEnv*, jobject);
static void (*window_release)(void*);
static int32_t (*window_geometry)(void*, int32_t, int32_t, int32_t);
static int32_t (*window_lock)(void*, WindowBuffer*, WindowRect*);
static int32_t (*window_unlock)(void*);
static int32_t (*window_rate)(void*, float, int8_t);
static void* (*choreographer_instance)(void);
static void (*choreographer_post)(void*, void (*)(long, void*), void*);
static void* (*looper_prepare)(int);
static int (*looper_poll)(int, int*, int*, void**);

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static void* window;
static int window_dirty;
static int server = -1;
static volatile int stop_thread;
static pthread_t thread, vsync_thread;
static volatile int frames_shown;

static struct FrameHeader source;
static const uint8_t* frames;
static size_t frame_size;
static int client_fd = -1;
static int latest = -1, shown_slot = -1;

static volatile int target_ms;
static volatile int paced;
static int64_t period, last_vsync, last_shown, shortest_gap = INT64_MAX;
static int vsyncs_measured, every = 1;
static volatile int hertz;

static FILE* stats_file;
static int64_t stats_time, stats_copy, stats_gap;
static volatile int stats_arrived;
static int stats_arrived_before, stats_shown_before, stats_repeated, stats_late;

static int load_android(void) {
    void* lib = dlopen("libandroid.so", RTLD_NOW);
    if (!lib)
        return -1;
    *(void**)&window_from_surface = dlsym(lib, "ANativeWindow_fromSurface");
    *(void**)&window_release = dlsym(lib, "ANativeWindow_release");
    *(void**)&window_geometry = dlsym(lib, "ANativeWindow_setBuffersGeometry");
    *(void**)&window_lock = dlsym(lib, "ANativeWindow_lock");
    *(void**)&window_unlock = dlsym(lib, "ANativeWindow_unlockAndPost");
    *(void**)&window_rate = dlsym(lib, "ANativeWindow_setFrameRate");
    *(void**)&choreographer_instance = dlsym(lib, "AChoreographer_getInstance");
    *(void**)&choreographer_post = dlsym(lib, "AChoreographer_postFrameCallback");
    *(void**)&looper_prepare = dlsym(lib, "ALooper_prepare");
    *(void**)&looper_poll = dlsym(lib, "ALooper_pollOnce");
    return window_from_surface && window_release && window_geometry && window_lock && window_unlock ? 0 : -1;
}

static int read_header(int client, struct FrameHeader* header, int* memory_fd) {
    char control[CMSG_SPACE(sizeof(int))];
    struct iovec payload = {header, sizeof(*header)};
    struct msghdr message = {0};
    message.msg_iov = &payload;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    if (recvmsg(client, &message, 0) != sizeof(*header) || header->magic != FRAME_MAGIC)
        return -1;
    struct cmsghdr* item = CMSG_FIRSTHDR(&message);
    if (!item || item->cmsg_type != SCM_RIGHTS)
        return -1;
    memcpy(memory_fd, CMSG_DATA(item), sizeof(int));
    return 0;
}

// Vulkan's BGRA surface format is swapped into the RGBA window format while copying.
static void copy_rows(WindowBuffer* out, const uint8_t* source, const struct FrameHeader* header) {
    const int swap = header->format == VK_FORMAT_B8G8R8A8_UNORM || header->format == VK_FORMAT_B8G8R8A8_SRGB;
    const uint32_t width = (uint32_t)out->width < header->width ? (uint32_t)out->width : header->width;
    const uint32_t height = (uint32_t)out->height < header->height ? (uint32_t)out->height : header->height;
    for (uint32_t y = 0; y < height; y++) {
        const uint32_t* in = (const uint32_t*)(source + y * header->stride);
        uint32_t* row = (uint32_t*)((uint8_t*)out->bits + (size_t)y * out->stride * 4);
        if (!swap) {
            memcpy(row, in, width * 4);
            continue;
        }
        for (uint32_t x = 0; x < width; x++) {
            const uint32_t pixel = in[x];
            row[x] = (pixel & 0xff00ff00) | (pixel & 0xff) << 16 | (pixel >> 16 & 0xff) | 0xff000000;
        }
    }
}

static int64_t now_ns(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return (int64_t)time.tv_sec * NS_PER_SECOND + time.tv_nsec;
}

static void configure_window(void) {
    window_geometry(window, (int32_t)source.width, (int32_t)source.height, WINDOW_FORMAT_RGBA_8888);
    const int ms = target_ms;
    if (window_rate && ms > 0)
        window_rate(window, (float)(int)(1000.0f / ms + 0.5f), FRAME_RATE_FIXED_SOURCE);
    window_dirty = 0;
}

// Draws the newest frame, then tells the engine to start the next one.
static void show_latest(void) {
    pthread_mutex_lock(&lock);
    const int slot = __atomic_load_n(&latest, __ATOMIC_ACQUIRE);
    if (frames && slot >= 0 && window) {
        if (window_dirty)
            configure_window();
        if (slot == shown_slot) {
            stats_repeated++;
        } else {
            const int64_t start = now_ns();
            WindowBuffer out;
            if (window_lock(window, &out, NULL) == 0) {
                copy_rows(&out, frames + frame_size * slot, &source);
                window_unlock(window);
                frames_shown++;
                shown_slot = slot;
            }
            const int64_t spent = now_ns() - start;
            if (spent > stats_copy)
                stats_copy = spent;
        }
    }
    if (client_fd >= 0 && target_ms > 0)
        send(client_fd, "t", 1, MSG_DONTWAIT | MSG_NOSIGNAL);
    pthread_mutex_unlock(&lock);
}

static void write_stats(int64_t now) {
    if (!stats_file || now - stats_time < STATS_NS)
        return;
    const int shown = frames_shown, arrived = stats_arrived;
    fprintf(stats_file, "%.0f s  %d Hz  %d vsync  arrivees %d  affichees %d  repetees %d  perdues %d  retards %d  copie %.1f ms  ecart max %.1f ms\n",
            (double)now / NS_PER_SECOND, hertz, every, arrived - stats_arrived_before, shown - stats_shown_before,
            stats_repeated, (arrived - stats_arrived_before) - (shown - stats_shown_before), stats_late,
            (double)stats_copy / 1e6, (double)stats_gap / 1e6);
    fflush(stats_file);
    stats_time = now;
    stats_arrived_before = arrived;
    stats_shown_before = shown;
    stats_repeated = stats_late = 0;
    stats_copy = stats_gap = 0;
}

// Follows the real screen: only the shortest gaps count, a late callback must not change the measured period.
static void measure_period(int64_t now) {
    const int64_t gap = now - last_vsync;
    last_vsync = now;
    if (gap > MIN_PERIOD_NS && gap < shortest_gap)
        shortest_gap = gap;
    if (++vsyncs_measured < VSYNCS_PER_MEASURE)
        return;
    vsyncs_measured = 0;
    if (shortest_gap < INT64_MAX) {
        period = shortest_gap;
        hertz = (int)((NS_PER_SECOND + period / 2) / period);
        const int ms = target_ms;
        const int count = (int)((ms * NS_PER_MS + period / 2) / period);
        every = count > 1 ? count : 1;
    }
    shortest_gap = INT64_MAX;
}

static void on_vsync(long time, void* choreographer) {
    choreographer_post(choreographer, on_vsync, choreographer);
    paced = 1;
    const int64_t now = time;
    measure_period(now);
    if (period == 0)
        return;
    // Counted from the real vsync times, so that a late callback keeps the same rhythm.
    const int vsyncs = (int)((now - last_shown + period / 2) / period);
    if (vsyncs < every)
        return;
    if (vsyncs > every)
        stats_late++;
    if (vsyncs < MAX_GAP_VSYNCS && now - last_shown > stats_gap)
        stats_gap = now - last_shown;
    last_shown = now;
    show_latest();
    write_stats(now);
}

static void* vsync_loop(void* unused) {
    (void)unused;
    setpriority(PRIO_PROCESS, 0, DISPLAY_NICE);
    looper_prepare(0);
    void* choreographer = choreographer_instance();
    if (!choreographer)
        return NULL;
    choreographer_post(choreographer, on_vsync, choreographer);
    while (!stop_thread)
        looper_poll(-1, NULL, NULL, NULL);
    return NULL;
}

static void show_frames(int client) {
    struct FrameHeader header;
    int memory_fd = -1;
    if (read_header(client, &header, &memory_fd))
        return;
    const size_t size = (size_t)header.stride * header.height;
    const uint8_t* mapped = mmap(NULL, size * header.slots, PROT_READ, MAP_SHARED, memory_fd, 0);
    close(memory_fd);
    if (mapped == MAP_FAILED)
        return;
    pthread_mutex_lock(&lock);
    source = header;
    frames = mapped;
    frame_size = size;
    client_fd = client;
    window_dirty = 1;
    shown_slot = -1;
    __atomic_store_n(&latest, -1, __ATOMIC_RELEASE);
    pthread_mutex_unlock(&lock);
    uint32_t slot;
    while (!stop_thread && read(client, &slot, sizeof(slot)) == sizeof(slot)) {
        if (slot >= header.slots)
            continue;
        __atomic_store_n(&latest, (int)slot, __ATOMIC_RELEASE);
        stats_arrived++;
        if (!paced)
            show_latest();
    }
    pthread_mutex_lock(&lock);
    frames = NULL;
    client_fd = -1;
    pthread_mutex_unlock(&lock);
    munmap((void*)mapped, size * header.slots);
}

static void* serve(void* unused) {
    (void)unused;
    while (!stop_thread) {
        const int client = accept(server, NULL, NULL);
        if (client < 0) {
            if (errno != EINTR)
                break;
            continue;
        }
        show_frames(client);
        close(client);
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL Java_com_nba2k14_lanceur_Ecran_demarrer(JNIEnv* env, jclass cls, jstring path, jstring rythme) {
    (void)cls;
    if (load_android())
        return JNI_FALSE;
    const char* stats_path = (*env)->GetStringUTFChars(env, rythme, NULL);
    stats_file = fopen(stats_path, "w");
    (*env)->ReleaseStringUTFChars(env, rythme, stats_path);
    const char* text = (*env)->GetStringUTFChars(env, path, NULL);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strncpy(address.sun_path, text, sizeof(address.sun_path) - 1);
    unlink(text);
    (*env)->ReleaseStringUTFChars(env, path, text);
    server = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server < 0 || bind(server, (struct sockaddr*)&address, sizeof(address)) || listen(server, 1))
        return JNI_FALSE;
    stop_thread = 0;
    if (choreographer_instance && choreographer_post && looper_prepare && looper_poll)
        pthread_create(&vsync_thread, NULL, vsync_loop, NULL);
    return pthread_create(&thread, NULL, serve, NULL) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_nba2k14_lanceur_Ecran_cadence(JNIEnv* env, jclass cls, jint ms) {
    (void)env;
    (void)cls;
    target_ms = ms;
    pthread_mutex_lock(&lock);
    window_dirty = 1;
    pthread_mutex_unlock(&lock);
}

JNIEXPORT void JNICALL Java_com_nba2k14_lanceur_Ecran_surface(JNIEnv* env, jclass cls, jobject surface) {
    (void)cls;
    // A surface before demarrer (it failed, or a test build without the engine): load what it needs first.
    if (!window_from_surface && load_android())
        return;
    void* next = surface ? window_from_surface(env, surface) : NULL;
    pthread_mutex_lock(&lock);
    if (window)
        window_release(window);
    window = next;
    window_dirty = 1;
    pthread_mutex_unlock(&lock);
}

JNIEXPORT jint JNICALL Java_com_nba2k14_lanceur_Ecran_images(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return frames_shown;
}

JNIEXPORT jint JNICALL Java_com_nba2k14_lanceur_Ecran_hertz(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return hertz;
}

// dup() leaves out close-on-exec, which Android sets on the descriptors it hands out.
JNIEXPORT jint JNICALL Java_com_nba2k14_lanceur_Ecran_garder(JNIEnv* env, jclass cls, jint fd) {
    (void)env;
    (void)cls;
    return dup(fd);
}

static char** strings(JNIEnv* env, jobjectArray array) {
    const jsize count = (*env)->GetArrayLength(env, array);
    char** out = calloc((size_t)count + 1, sizeof(char*));
    for (jsize i = 0; out && i < count; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        const char* text = (*env)->GetStringUTFChars(env, item, NULL);
        out[i] = strdup(text);
        (*env)->ReleaseStringUTFChars(env, item, text);
        (*env)->DeleteLocalRef(env, item);
    }
    return out;
}

static void free_strings(char** list) {
    for (char** item = list; list && *item; item++)
        free(*item);
    free(list);
}

// The program keeps every descriptor without close-on-exec: the game's files (garder). Output and errors go to
// a file, input is empty.
JNIEXPORT jint JNICALL Java_com_nba2k14_lanceur_Ecran_lancer(JNIEnv* env, jclass cls, jobjectArray arguments,
                                                           jobjectArray environment, jstring folder, jstring output) {
    (void)cls;
    char** argv = strings(env, arguments);
    char** envp = strings(env, environment);
    const char* dir = (*env)->GetStringUTFChars(env, folder, NULL);
    const char* log = (*env)->GetStringUTFChars(env, output, NULL);
    int result = -EINVAL;
    const int out = open(log, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0660);
    const int in = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (argv && envp && out >= 0 && in >= 0) {
        posix_spawn_file_actions_t actions;
        posix_spawn_file_actions_init(&actions);
        posix_spawn_file_actions_adddup2(&actions, in, 0);
        posix_spawn_file_actions_adddup2(&actions, out, 1);
        posix_spawn_file_actions_adddup2(&actions, out, 2);
        posix_spawn_file_actions_addchdir_np(&actions, dir);
        posix_spawnattr_t attributes;
        posix_spawnattr_init(&attributes);
        // The app's blocked signals are not the engine's, nor its ignored SIGPIPE (handlers end with exec).
        sigset_t none, ignored;
        sigemptyset(&none);
        sigemptyset(&ignored);
        sigaddset(&ignored, SIGPIPE);
        posix_spawnattr_setsigmask(&attributes, &none);
        posix_spawnattr_setsigdefault(&attributes, &ignored);
        posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF);
        pid_t pid = 0;
        const int error = posix_spawn(&pid, argv[0], &actions, &attributes, argv, envp);
        result = error ? -error : (int)pid;
        __android_log_print(ANDROID_LOG_INFO, "nba2k14", "started %s: %d", argv[0], result);
        posix_spawnattr_destroy(&attributes);
        posix_spawn_file_actions_destroy(&actions);
    } else if (out < 0 || in < 0) {
        result = -errno;
    }
    if (out >= 0)
        close(out);
    if (in >= 0)
        close(in);
    (*env)->ReleaseStringUTFChars(env, folder, dir);
    (*env)->ReleaseStringUTFChars(env, output, log);
    free_strings(argv);
    free_strings(envp);
    return result;
}

// How the engine ended goes to logcat (tag nba2k14).
JNIEXPORT jboolean JNICALL Java_com_nba2k14_lanceur_Ecran_vivant(JNIEnv* env, jclass cls, jint pid) {
    (void)env;
    (void)cls;
    int status = 0;
    const pid_t done = waitpid(pid, &status, WNOHANG);
    if (done == pid) {
        if (WIFEXITED(status))
            __android_log_print(ANDROID_LOG_INFO, "nba2k14", "engine %d exited with code %d", pid, WEXITSTATUS(status));
        else if (WIFSIGNALED(status))
            __android_log_print(ANDROID_LOG_INFO, "nba2k14", "engine %d killed by signal %d", pid, WTERMSIG(status));
    }
    return done == 0 ? JNI_TRUE : JNI_FALSE;
}

// Asks the engine to stop, then stops it after 2 s.
JNIEXPORT void JNICALL Java_com_nba2k14_lanceur_Ecran_arreter(JNIEnv* env, jclass cls, jint pid) {
    (void)env;
    (void)cls;
    if (kill(pid, SIGTERM))
        return;
    for (int i = 0; i < 20; i++) {
        if (waitpid(pid, NULL, WNOHANG) != 0)
            return;
        usleep(100 * 1000);
    }
    kill(pid, SIGKILL);
    waitpid(pid, NULL, 0);
}
