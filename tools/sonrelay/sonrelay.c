// Plays the engine's sound on Android: listens on a unix socket (the Bachata audio protocol) and writes to AAudio.
// Build in Termux: clang -O2 -o sonrelay sonrelay.c -ldl -lpthread
// usage: sonrelay <socket path>
// Message: code u8, payload size u32 LE, payload. 4 = prepare (channels u8, type u8, rate u32, buffer u32), 5 = PCM, 0 = end.
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define AAUDIO_FORMAT_PCM_I16 1
#define AAUDIO_SHARING_MODE_SHARED 1
#define AAUDIO_PERFORMANCE_MODE_NONE 10
#define AAUDIO_USAGE_GAME 14
#define BUFFER_CAPACITY_FRAMES 9600 // 200 ms: the game's frames arrive in bursts
#define WRITE_TIMEOUT_NS 100000000LL

static int32_t (*create_builder)(void**);
static void (*set_rate)(void*, int32_t);
static void (*set_channels)(void*, int32_t);
static void (*set_format)(void*, int32_t);
static void (*set_performance)(void*, int32_t);
static void (*set_sharing)(void*, int32_t);
static void (*set_usage)(void*, int32_t);
static void (*set_capacity)(void*, int32_t);
static int32_t (*open_stream)(void*, void**);
static int32_t (*delete_builder)(void*);
static int32_t (*start_stream)(void*);
static int32_t (*write_stream)(void*, const void*, int32_t, int64_t);
static int32_t (*xruns)(void*);
static int32_t (*close_stream)(void*);

static int load_aaudio(void) {
    void* lib = dlopen("libaaudio.so", RTLD_NOW);
    if (!lib) {
        printf("libaaudio: %s\n", dlerror());
        return -1;
    }
#define LOAD(var, name)                                                                            \
    *(void**)&var = dlsym(lib, name);                                                              \
    if (!var) {                                                                                    \
        printf("missing %s\n", name);                                                              \
        return -1;                                                                                 \
    }
    LOAD(create_builder, "AAudio_createStreamBuilder")
    LOAD(set_rate, "AAudioStreamBuilder_setSampleRate")
    LOAD(set_channels, "AAudioStreamBuilder_setChannelCount")
    LOAD(set_format, "AAudioStreamBuilder_setFormat")
    LOAD(set_performance, "AAudioStreamBuilder_setPerformanceMode")
    LOAD(set_sharing, "AAudioStreamBuilder_setSharingMode")
    LOAD(set_usage, "AAudioStreamBuilder_setUsage")
    LOAD(set_capacity, "AAudioStreamBuilder_setBufferCapacityInFrames")
    LOAD(open_stream, "AAudioStreamBuilder_openStream")
    LOAD(delete_builder, "AAudioStreamBuilder_delete")
    LOAD(start_stream, "AAudioStream_requestStart")
    LOAD(write_stream, "AAudioStream_write")
    LOAD(xruns, "AAudioStream_getXRunCount")
    LOAD(close_stream, "AAudioStream_close")
    return 0;
}

static int read_all(int fd, void* target, size_t size) {
    uint8_t* p = target;
    while (size) {
        ssize_t n = read(fd, p, size);
        if (n <= 0)
            return -1;
        p += n;
        size -= (size_t)n;
    }
    return 0;
}

static void* open_output(int32_t rate) {
    void* builder = NULL;
    void* stream = NULL;
    if (create_builder(&builder))
        return NULL;
    set_rate(builder, rate);
    set_channels(builder, 2);
    set_format(builder, AAUDIO_FORMAT_PCM_I16);
    set_performance(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    set_sharing(builder, AAUDIO_SHARING_MODE_SHARED);
    set_usage(builder, AAUDIO_USAGE_GAME);
    set_capacity(builder, BUFFER_CAPACITY_FRAMES);
    int32_t result = open_stream(builder, &stream);
    delete_builder(builder);
    if (result || start_stream(stream)) {
        printf("aaudio open failed: %d\n", result);
        return NULL;
    }
    return stream;
}

static void* serve(void* arg) {
    int fd = (int)(intptr_t)arg;
    void* stream = NULL;
    uint8_t* pcm = NULL;
    uint32_t capacity = 0;
    uint64_t frames = 0, dropped = 0;
    int peak = 0;
    time_t last = time(NULL);
    for (;;) {
        uint8_t header[5];
        if (read_all(fd, header, sizeof(header)))
            break;
        const uint32_t size = header[1] | header[2] << 8 | header[3] << 16 | (uint32_t)header[4] << 24;
        if (size > capacity) {
            pcm = realloc(pcm, size);
            capacity = size;
        }
        if (!pcm || read_all(fd, pcm, size))
            break;
        if (header[0] == 0)
            break;
        if (header[0] == 4 && size >= 10 && !stream) {
            const int32_t rate = (int32_t)(pcm[2] | pcm[3] << 8 | pcm[4] << 16 | (uint32_t)pcm[5] << 24);
            stream = open_output(rate);
            printf("open rate=%d channels=%d stream=%s\n", rate, pcm[0], stream ? "ok" : "failed");
            fflush(stdout);
        } else if (header[0] == 5 && stream) {
            const int32_t count = (int32_t)(size / 4);
            const int32_t written = write_stream(stream, pcm, count, WRITE_TIMEOUT_NS);
            frames += written > 0 ? (uint64_t)written : 0;
            dropped += written >= 0 ? (uint64_t)(count - written) : (uint64_t)count;
            const int16_t* s = (const int16_t*)pcm;
            for (int32_t i = 0; i < count * 2; i++) {
                const int v = abs(s[i]);
                peak = v > peak ? v : peak;
            }
        }
        const time_t now = time(NULL);
        if (now - last >= 5 && stream) {
            printf("frames=%llu dropped=%llu peak=%d xruns=%d\n", (unsigned long long)frames,
                   (unsigned long long)dropped, peak, xruns(stream));
            fflush(stdout);
            peak = 0;
            last = now;
        }
    }
    if (stream)
        close_stream(stream);
    free(pcm);
    close(fd);
    return NULL;
}

int main(int argc, char** argv) {
    if (argc < 2 || load_aaudio())
        return 1;
    int server = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    strncpy(address.sun_path, argv[1], sizeof(address.sun_path) - 1);
    unlink(argv[1]);
    if (bind(server, (struct sockaddr*)&address, sizeof(address)) || listen(server, 8)) {
        perror("socket");
        return 1;
    }
    puts("sonrelay ready");
    fflush(stdout);
    for (;;) {
        const int client = accept(server, NULL, NULL);
        if (client < 0)
            continue;
        pthread_t thread;
        pthread_create(&thread, NULL, serve, (void*)(intptr_t)client);
        pthread_detach(thread);
    }
}
