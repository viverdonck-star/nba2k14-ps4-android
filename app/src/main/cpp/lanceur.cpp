// NBA 2K14's menu on Android, drawn with Dear ImGui on SDL3's GPU API (Vulkan), with the art and music of the
// player's own game: sce_sys/pic1.png for the background, the soundtrack in archive 1L (2K audio files,
// ATRAC9) for the music. Nothing of the game is in the APK.
//
// It runs in Accueil (Java), in the app's main process. The game folder is picked once with Android's folder
// picker; Java opens its files and hands them over as /proc/self/fd/<n> (Accueil.openFolder). Play starts the
// game's activity (JeuActivity) in a process of its own; the menu and its music come back when it ends.
//
// Settings and the log are in the app's own folder on shared storage (Android/data/<package>/files).

#include <jni.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cctype>
#include <cfloat>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <numeric>
#include <optional>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <type_traits>
#include <vector>

#include <imgui.h>
#include <imgui_impl_sdl3.h>
#include <imgui_impl_sdlgpu3.h>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#include <stb_image.h>

#include "libatrac9.h"

namespace {

// ------------------------------------------------------------------ log

FILE* g_log = nullptr;

void Log(const char* format, ...) {
  if (!g_log) return;
  va_list args;
  va_start(args, format);
  vfprintf(g_log, format, args);
  va_end(args);
  fputc('\n', g_log);
  fflush(g_log);
}

// ------------------------------------------------------------------ language

// The menu's language, French or English: "langue" in reglages.ini, the system's at first.
bool g_english = false;

const char* L(const char* french, const char* english) { return g_english ? english : french; }

// ------------------------------------------------------------------ Java

// Accueil's static methods (the activity's own class: a thread SDL made can't find app classes by name).
jclass ActivityClass(JNIEnv* env) {
  auto activity = static_cast<jobject>(SDL_GetAndroidActivity());
  jclass type = env->GetObjectClass(activity);
  env->DeleteLocalRef(activity);
  return type;
}

std::string ToString(JNIEnv* env, jstring text) {
  std::string result;
  if (text) {
    const char* chars = env->GetStringUTFChars(text, nullptr);
    result = chars;
    env->ReleaseStringUTFChars(text, chars);
    env->DeleteLocalRef(text);
  }
  return result;
}

// A static method taking strings and returning a string ("" for null), a boolean or nothing.
template <typename Result>
Result Call(const char* method, const std::vector<std::string>& args = {}) {
  auto* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
  jclass type = ActivityClass(env);
  std::string signature = "(";
  for (size_t i = 0; i < args.size(); ++i) signature += "Ljava/lang/String;";
  if constexpr (std::is_same_v<Result, std::string>) {
    signature += ")Ljava/lang/String;";
  } else if constexpr (std::is_same_v<Result, bool>) {
    signature += ")Z";
  } else {
    signature += ")V";
  }
  jmethodID id = env->GetStaticMethodID(type, method, signature.c_str());
  std::vector<jvalue> values(args.size());
  for (size_t i = 0; i < args.size(); ++i) values[i].l = env->NewStringUTF(args[i].c_str());
  auto release = [&] {
    for (jvalue& value : values) env->DeleteLocalRef(value.l);
    env->DeleteLocalRef(type);
  };
  if constexpr (std::is_same_v<Result, std::string>) {
    auto text = static_cast<jstring>(env->CallStaticObjectMethodA(type, id, values.data()));
    release();
    return ToString(env, text);
  } else if constexpr (std::is_same_v<Result, bool>) {
    bool result = env->CallStaticBooleanMethodA(type, id, values.data());
    release();
    return result;
  } else {
    env->CallStaticVoidMethodA(type, id, values.data());
    release();
  }
}

// The folder picker's answer (Accueil.onActivityResult, on Java's thread), taken by the frame loop.
std::mutex g_picked_mutex;
std::optional<std::string> g_picked;

// ------------------------------------------------------------------ the game's files

// The open files of the game folder: "<relative path>\t<size>\t/proc/self/fd/<n>" lines (Accueil.openFolder).
// Read with pread on the descriptor, which Java keeps open.
class GameFiles {
 public:
  struct File {
    int64_t size = 0;
    int fd = -1;  // -1: listed, but Android couldn't open it
  };

  explicit GameFiles(const std::string& lines) {
    std::istringstream in(lines);
    std::string line;
    while (std::getline(in, line)) {
      size_t a = line.find('\t'), b = a == std::string::npos ? a : line.find('\t', a + 1);
      if (b == std::string::npos) continue;
      File file;
      file.size = std::atoll(line.substr(a + 1, b - a - 1).c_str());
      std::string path = line.substr(b + 1);
      const char* prefix = "/proc/self/fd/";
      if (path.rfind(prefix, 0) == 0) file.fd = std::atoi(path.c_str() + std::strlen(prefix));
      files_[line.substr(0, a)] = file;
    }
  }

  const File* Find(const std::string& path) const {
    auto found = files_.find(path);
    return found == files_.end() ? nullptr : &found->second;
  }

  // Up to size bytes from offset; fewer if the file is shorter, none if it can't be read.
  std::vector<uint8_t> Read(const std::string& path, int64_t offset, size_t size) const {
    const File* file = Find(path);
    std::vector<uint8_t> data;
    if (!file || file->fd < 0 || offset >= file->size) return data;
    data.resize(size_t(std::min<int64_t>(int64_t(size), file->size - offset)));
    size_t done = 0;
    while (done < data.size()) {
      ssize_t got = pread(file->fd, data.data() + done, data.size() - done, off_t(offset + done));
      if (got <= 0) break;
      done += size_t(got);
    }
    data.resize(done);
    return data;
  }

  std::vector<uint8_t> ReadAll(const std::string& path) const {
    const File* file = Find(path);
    return file ? Read(path, 0, size_t(file->size)) : std::vector<uint8_t>{};
  }

  size_t count() const { return files_.size(); }

 private:
  std::map<std::string, File> files_;
};

// ------------------------------------------------------------------ the check

// NBA 2K14 for PS4 (CUSA00104) as the player's copy has it: eboot.bin and the 41 archives, with their sizes.
constexpr const char* kTitleId = "CUSA00104";
struct Expected {
  const char* path;
  int64_t size;
};
constexpr Expected kExpected[] = {
    {"eboot.bin", 37204658},  {"0A", 153290277},   {"0B", 2290596049},  {"0C", 11208213849},
    {"0D", 376871912},        {"0E", 274429807},   {"0F", 282027616},   {"0G", 293313872},
    {"0H", 215708393},        {"0I", 320947424},   {"0J", 241145527},   {"0K", 290131405},
    {"0L", 276564277},        {"0M", 294072362},   {"0N", 52784065},    {"0O", 286815553},
    {"0P", 297438239},        {"0Q", 233908277},   {"0R", 285530329},   {"0S", 287601699},
    {"0T", 273978524},        {"0U", 219876707},   {"0V", 212013786},   {"0W", 278868849},
    {"0X", 248228549},        {"0Y", 290214910},   {"0Z", 267776312},   {"1A", 304457060},
    {"1B", 118645579},        {"1C", 274332126},   {"1D", 281267445},   {"1E", 306579241},
    {"1F", 295394296},        {"1G", 291873448},   {"1H", 245538258},   {"1I", 1476919923},
    {"1J", 568788389},        {"1K", 906696192},   {"1L", 5924016759},  {"1M", 7943951936},
    {"1N", 2051265960},       {"1O", 932383984},
};

// param.sfo's text values: a PSF header, then (key offset u16, format u16, length u32, max u32, data offset
// u32) entries.
std::map<std::string, std::string> ReadSfo(const std::vector<uint8_t>& sfo) {
  std::map<std::string, std::string> values;
  auto u32 = [&](size_t at) {
    return at + 4 <= sfo.size() ? uint32_t(sfo[at] | sfo[at + 1] << 8 | sfo[at + 2] << 16 | uint32_t(sfo[at + 3]) << 24)
                                : 0u;
  };
  if (sfo.size() < 20 || std::memcmp(sfo.data(), "\0PSF", 4) != 0) return values;
  uint32_t keys = u32(8), data = u32(12), count = u32(16);
  for (uint32_t i = 0; i < count && 20 + i * 16 + 16 <= sfo.size(); ++i) {
    size_t entry = 20 + i * 16;
    uint32_t key = keys + (sfo[entry] | sfo[entry + 1] << 8);
    uint16_t format = uint16_t(sfo[entry + 2] | sfo[entry + 3] << 8);
    uint32_t length = u32(entry + 4), offset = data + u32(entry + 12);
    if (key >= sfo.size() || format != 0x0204 || offset + length > sfo.size()) continue;
    std::string name(reinterpret_cast<const char*>(&sfo[key]));
    std::string value(reinterpret_cast<const char*>(&sfo[offset]), length);
    value.erase(std::find(value.begin(), value.end(), '\0'), value.end());
    values[name] = value;
  }
  return values;
}

struct Verdict {
  bool ready = false;
  std::string headline;  // "Prêt à jouer", or what is wrong
  std::string detail;
  std::string version;
};

// "a, b, c" for the first few names, "…" after.
std::string List(const std::vector<std::string>& names) {
  std::string text;
  for (size_t i = 0; i < names.size() && i < 6; ++i) text += (i ? ", " : "") + names[i];
  if (names.size() > 6) text += "…";
  return text;
}

Verdict Check(const GameFiles& files) {
  Verdict verdict;
  std::map<std::string, std::string> sfo = ReadSfo(files.ReadAll("sce_sys/param.sfo"));
  if (!files.Find("eboot.bin") || sfo.empty()) {
    verdict.headline = L("Ce dossier n'est pas un jeu PS4", "This folder isn't a PS4 game");
    verdict.detail = L("Choisis le dossier qui contient eboot.bin et sce_sys (par exemple PS4-2K14).",
                       "Pick the folder that holds eboot.bin and sce_sys (PS4-2K14, for instance).");
    return verdict;
  }
  if (sfo["TITLE_ID"] != kTitleId) {
    verdict.headline = L("Ce n'est pas NBA 2K14", "This isn't NBA 2K14");
    verdict.detail = std::string(L("Ce dossier contient « ", "This folder holds “")) + sfo["TITLE"] +
                     L(" » (", "” (") + sfo["TITLE_ID"] + L("). Il faut NBA 2K14 PS4 (", "). It needs NBA 2K14 for PS4 (") +
                     kTitleId + ").";
    return verdict;
  }
  verdict.version = sfo["APP_VER"].empty() ? sfo["VERSION"] : sfo["APP_VER"];
  std::vector<std::string> missing, unreadable, different;
  for (const Expected& expected : kExpected) {
    const GameFiles::File* file = files.Find(expected.path);
    if (!file) {
      missing.push_back(expected.path);
    } else if (file->fd < 0) {
      unreadable.push_back(expected.path);
    } else if (file->size != expected.size) {
      different.push_back(expected.path);
    }
  }
  if (!missing.empty()) {
    verdict.headline = L("Il manque des fichiers du jeu", "Some game files are missing");
    verdict.detail = std::to_string(missing.size()) + L(" fichier(s) absent(s) : ", " missing file(s): ") +
                     List(missing) + ".";
  } else if (!unreadable.empty()) {
    verdict.headline = L("Android n'a pas pu ouvrir le jeu", "Android couldn't open the game");
    verdict.detail = L("Fichiers illisibles : ", "Unreadable files: ") + List(unreadable) +
                     L(". Choisis à nouveau le dossier.", ". Pick the folder again.");
  } else if (!different.empty()) {
    verdict.headline = L("Fichiers incomplets", "Incomplete files");
    verdict.detail = L("Ces fichiers n'ont pas la bonne taille, la copie est peut-être incomplète : ",
                       "These files don't have the right size, the copy may be incomplete: ") +
                     List(different) + ".";
  } else {
    verdict.ready = true;
    verdict.headline = L("Prêt à jouer", "Ready to play");
    verdict.detail = "NBA 2K14 · " + std::string(kTitleId) + " · version " + verdict.version;
  }
  Log("check: %s (%s)", verdict.headline.c_str(), verdict.detail.c_str());
  return verdict;
}

// ------------------------------------------------------------------ images

struct Texture {
  std::shared_ptr<SDL_GPUTexture> view;
  int width = 0, height = 0;
  explicit operator bool() const { return view != nullptr; }
  ImTextureRef ref() const { return ImTextureRef(ImTextureID(reinterpret_cast<intptr_t>(view.get()))); }
};

Texture LoadPng(SDL_GPUDevice* device, const std::vector<uint8_t>& png) {
  Texture texture;
  int width = 0, height = 0, channels = 0;
  stbi_uc* rgba = png.empty() ? nullptr
                              : stbi_load_from_memory(png.data(), int(png.size()), &width, &height, &channels, 4);
  if (!rgba) return texture;
  SDL_GPUTextureCreateInfo info = {};
  info.type = SDL_GPU_TEXTURETYPE_2D;
  info.format = SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM;
  info.usage = SDL_GPU_TEXTUREUSAGE_SAMPLER;
  info.width = uint32_t(width);
  info.height = uint32_t(height);
  info.layer_count_or_depth = 1;
  info.num_levels = 1;
  SDL_GPUTexture* made = SDL_CreateGPUTexture(device, &info);
  uint32_t bytes = uint32_t(width) * uint32_t(height) * 4;
  SDL_GPUTransferBufferCreateInfo transfer_info = {SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD, bytes, 0};
  SDL_GPUTransferBuffer* transfer = made ? SDL_CreateGPUTransferBuffer(device, &transfer_info) : nullptr;
  if (void* mapped = transfer ? SDL_MapGPUTransferBuffer(device, transfer, false) : nullptr) {
    std::memcpy(mapped, rgba, bytes);
    SDL_UnmapGPUTransferBuffer(device, transfer);
    SDL_GPUCommandBuffer* commands = SDL_AcquireGPUCommandBuffer(device);
    SDL_GPUCopyPass* pass = SDL_BeginGPUCopyPass(commands);
    SDL_GPUTextureTransferInfo source = {transfer, 0, uint32_t(width), uint32_t(height)};
    SDL_GPUTextureRegion region = {};
    region.texture = made;
    region.w = uint32_t(width);
    region.h = uint32_t(height);
    region.d = 1;
    SDL_UploadToGPUTexture(pass, &source, &region, false);
    SDL_EndGPUCopyPass(pass);
    SDL_SubmitGPUCommandBuffer(commands);
  }
  if (transfer) SDL_ReleaseGPUTransferBuffer(device, transfer);
  stbi_image_free(rgba);
  if (!made) return texture;
  texture.view = std::shared_ptr<SDL_GPUTexture>(made, [device](SDL_GPUTexture* t) { SDL_ReleaseGPUTexture(device, t); });
  texture.width = width;
  texture.height = height;
  return texture;
}

// ------------------------------------------------------------------ music

// The soundtrack (the game's newaudio/jukeboxmusic): 2K audio files stored as they are, one after the other,
// in archive 1L. The first 16 stereo files of 1L, used before, were crowd noise. Where they are in the
// player's copy, found on the PC (offset of the header, data size at 0x1C); checked before use.
struct Song {
  int64_t offset;
  uint32_t size;
};
constexpr Song kSongs[] = {
    {1737363311, 4829184}, {1742192583, 5207552}, {1747400223, 5396992}, {1752797303, 5957120},
    {1758754511, 4141056}, {1762895655, 5992960}, {1768888703, 6183936}, {1775072727, 5382656},
    {1780455471, 4304896}, {1784760455, 5602816}, {1790363359, 5454848}, {1795818295, 7012352},
    {1802830735, 6180864}, {1809011687, 6094848}, {1815106623, 6195200}, {1821301911, 6811648},
    {1828113647, 7777792}, {1837352631, 5521920}, {1842874639, 4588544}, {1847463271, 5352960},
};
constexpr uint8_t kAudioMagic[4] = {0x7E, 0xA1, 0xCF, 0xBB};
constexpr size_t kAudioHeader = 0x58;  // magic ... fmt (WAVEFORMATEXTENSIBLE + ATRAC9), then the data

uint32_t Le32(const uint8_t* p) { return p[0] | p[1] << 8 | p[2] << 16 | uint32_t(p[3]) << 24; }

// One song: empty if it isn't where expected.
std::vector<uint8_t> LoadSong(const GameFiles& files, const Song& song) {
  std::vector<uint8_t> audio = files.Read("1L", song.offset, kAudioHeader + size_t(song.size));
  if (audio.size() != kAudioHeader + song.size || std::memcmp(audio.data(), kAudioMagic, 4) != 0 ||
      Le32(&audio[0x1C]) != song.size) {
    return {};
  }
  return audio;
}

// Plays the songs in turn on a thread: read and decoded a song at a time, about a second ahead.
class Music {
 public:
  explicit Music(const GameFiles& files) : files_(files) {
    SDL_AudioSpec spec = {SDL_AUDIO_S16, 2, 48000};
    stream_ = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, nullptr, nullptr);
    if (!stream_) {
      Log("no audio: %s", SDL_GetError());
      return;
    }
    thread_ = std::thread([this] { Run(); });
  }

  ~Music() {
    stop_ = true;
    if (thread_.joinable()) thread_.join();
    if (stream_) SDL_DestroyAudioStream(stream_);
  }

  // 0 to 1.
  void SetVolume(float volume) {
    volume_ = volume;
    Apply();
  }

  // Silent while the game runs.
  void SetPaused(bool paused) {
    paused_ = paused;
    Apply();
  }

  void Skip() {
    skip_ = true;
    if (stream_) SDL_ClearAudioStream(stream_);
  }

  int song() const { return song_; }
  bool playing() const { return stream_ && song_ >= 0; }

 private:
  void Apply() {
    if (!stream_) return;
    SDL_SetAudioStreamGain(stream_, volume_);
    if (paused_ || volume_ <= 0) {
      SDL_PauseAudioStreamDevice(stream_);
    } else {
      SDL_ResumeAudioStreamDevice(stream_);
    }
  }

  void Run() {
    constexpr int kAhead = 48000 * 2 * 2;  // a second of 16-bit stereo
    // Random order: every song once per round, a new order each round, never the same song twice in a row.
    const int count = int(std::size(kSongs));
    std::vector<int> order(size_t(count), 0);
    std::iota(order.begin(), order.end(), 0);
    std::mt19937 random(std::random_device{}());
    size_t next = order.size();
    int last = -1, failures = 0;
    while (!stop_ && failures < count) {
      if (next >= order.size()) {
        std::shuffle(order.begin(), order.end(), random);
        if (count > 1 && order[0] == last) std::swap(order[0], order[size_t(count) - 1]);
        next = 0;
      }
      int index = order[next++];
      last = index;
      const Song& song = kSongs[size_t(index)];
      std::vector<uint8_t> audio = LoadSong(files_, song);
      if (audio.size() < kAudioHeader) {
        Log("song %d not found where expected", index + 1);
        failures++;
        continue;
      }
      failures = 0;
      song_ = index;
      skip_ = false;
      Decode(audio, kAhead);
    }
    song_ = -1;
  }

  // Block after block: each has the header (its data size at 0x1C), then its superframes; the next one starts
  // a few bytes after (aligned).
  void Decode(const std::vector<uint8_t>& audio, int ahead) {
    const uint8_t* config = &audio[0x24 + 44];  // fmt + 18 (WAVEFORMATEX) + 26 (extension up to the config)
    void* decoder = Atrac9GetHandle();
    Atrac9CodecInfo info = {};
    if (Atrac9InitDecoder(decoder, const_cast<uint8_t*>(config)) != 0 ||
        Atrac9GetCodecInfo(decoder, &info) != 0 || info.channels != 2 || info.samplingRate != 48000) {
      Atrac9ReleaseHandle(decoder);
      return;
    }
    std::vector<int16_t> pcm(size_t(info.frameSamples) * info.channels);
    size_t block = 0;
    while (!stop_ && !skip_ && block + kAudioHeader <= audio.size() &&
           std::memcmp(&audio[block], kAudioMagic, 4) == 0) {
      size_t pos = block + kAudioHeader;
      size_t end = std::min(audio.size(), pos + Le32(&audio[block + 0x1C]));
      for (; pos + size_t(info.superframeSize) <= end && !stop_ && !skip_; pos += size_t(info.superframeSize)) {
        size_t at = pos;
        for (int frame = 0; frame < info.framesInSuperframe; ++frame) {
          int used = 0;
          if (Atrac9Decode(decoder, &audio[at], pcm.data(), &used, 0) != 0) break;
          SDL_PutAudioStreamData(stream_, pcm.data(), int(pcm.size() * sizeof(int16_t)));
          at += size_t(used);
        }
        while (!stop_ && !skip_ && (paused_ || SDL_GetAudioStreamQueued(stream_) > ahead)) SDL_Delay(30);
      }
      // The next block's header, within a few bytes.
      size_t next = end;
      while (next + 4 <= audio.size() && next < end + 64 && std::memcmp(&audio[next], kAudioMagic, 4) != 0) next += 4;
      if (next >= end + 64) break;
      block = next;
    }
    Atrac9ReleaseHandle(decoder);
  }

  const GameFiles& files_;
  SDL_AudioStream* stream_ = nullptr;
  std::thread thread_;
  std::atomic<bool> stop_{false}, skip_{false}, paused_{false};
  std::atomic<float> volume_{0.7f};
  std::atomic<int> song_{-1};
};

// ------------------------------------------------------------------ settings

// reglages.ini in the app's folder: "key=value" lines.
class Settings {
 public:
  explicit Settings(std::string path) : path_(std::move(path)) {
    std::ifstream in(path_);
    std::string line;
    while (std::getline(in, line)) {
      size_t eq = line.find('=');
      if (eq != std::string::npos) values_[line.substr(0, eq)] = line.substr(eq + 1);
    }
  }
  std::string Get(const std::string& key, const std::string& fallback) const {
    auto found = values_.find(key);
    return found == values_.end() ? fallback : found->second;
  }
  int GetInt(const std::string& key, int fallback) const { return std::atoi(Get(key, std::to_string(fallback)).c_str()); }
  void Set(const std::string& key, const std::string& value) {
    values_[key] = value;
    std::ofstream out(path_);
    for (const auto& [k, v] : values_) out << k << '=' << v << '\n';
  }

 private:
  std::string path_;
  std::map<std::string, std::string> values_;
};

// ------------------------------------------------------------------ drawing

// The game's colours (its logo's red, the cover's dark reds).
constexpr ImU32 kRed = IM_COL32(227, 27, 35, 255);
constexpr ImU32 kWhite = IM_COL32(255, 255, 255, 255);
constexpr ImU32 kGrey = IM_COL32(200, 200, 206, 255);
constexpr ImU32 kDim = IM_COL32(150, 150, 158, 255);
constexpr ImU32 kGreen = IM_COL32(84, 214, 120, 255);
constexpr ImU32 kOrange = IM_COL32(255, 180, 60, 255);
constexpr ImU32 kSoftRed = IM_COL32(255, 120, 110, 255);

struct Fonts {
  ImFont* bold = nullptr;     // Oswald Bold, in the APK (assets/polices)
  ImFont* regular = nullptr;  // Oswald Regular
  bool fake_bold = false;     // the system's fonts instead: titles thickened by hand
};

// What the screen is drawn with: the draw list, the scale (the layout is made for 1920x1080) and the fonts.
struct Painter {
  ImDrawList* list;
  float scale;
  Fonts fonts;

  float S(float value) const { return value * scale; }

  // Oswald Regular is narrow and thin: it is drawn a little larger to read as well as the titles.
  float Size(ImFont* font, float size) const {
    return S(font == fonts.regular && !fonts.fake_bold ? size * 1.15f : size);
  }

  ImVec2 Measure(ImFont* font, float size, const std::string& text, float wrap = 0) const {
    return font->CalcTextSizeA(Size(font, size), FLT_MAX, wrap, text.c_str());
  }

  // The system's "bold" font draws as Roboto Regular on Android 15: it is thickened by a second pass.
  void Text(ImFont* font, float size, ImVec2 at, ImU32 colour, const std::string& text, float wrap = 0) const {
    list->AddText(font, Size(font, size), at, colour, text.c_str(), nullptr, wrap);
    if (font == fonts.bold && fonts.fake_bold) {
      list->AddText(font, S(size), ImVec2(at.x + S(size) * 0.03f, at.y), colour, text.c_str(), nullptr, wrap);
    }
  }

  // A slanted bar, as the game's own menus: the top edge leans right.
  void Slanted(ImVec2 min, ImVec2 max, ImU32 colour) const {
    float lean = (max.y - min.y) * 0.25f;
    list->AddQuadFilled(ImVec2(min.x + lean, min.y), ImVec2(max.x + lean, min.y), ImVec2(max.x, max.y),
                        ImVec2(min.x, max.y), colour);
  }

  // The picture over the whole screen, cropped to keep its proportions.
  void Cover(const Texture& texture, ImVec2 size) const {
    if (!texture) {
      list->AddRectFilledMultiColor(ImVec2(0, 0), size, IM_COL32(70, 4, 8, 255), IM_COL32(150, 10, 18, 255),
                                    IM_COL32(150, 10, 18, 255), IM_COL32(70, 4, 8, 255));
      return;
    }
    float image = float(texture.width) / float(texture.height), screen = size.x / size.y;
    ImVec2 uv0(0, 0), uv1(1, 1);
    if (screen > image) {
      float visible = image / screen;
      uv0.y = (1 - visible) / 2, uv1.y = 1 - uv0.y;
    } else {
      float visible = screen / image;
      uv0.x = (1 - visible) / 2, uv1.x = 1 - uv0.x;
    }
    list->AddImage(texture.ref(), ImVec2(0, 0), size, uv0, uv1);
  }
};

// A tap or click on a rectangle, in the frame's full-screen window.
bool Tapped(const char* id, ImVec2 min, ImVec2 max) {
  ImGui::SetCursorScreenPos(min);
  return ImGui::InvisibleButton(id, ImVec2(std::max(1.0f, max.x - min.x), std::max(1.0f, max.y - min.y)));
}

enum class Command { kUp, kDown, kLeft, kRight, kAccept, kBack, kSkipSong };

// ------------------------------------------------------------------ the menu

// The buttons the player can reassign (Manette.java, TOUCHES_ODIN and BOUTONS_JEU, in the same order): for each
// PS4 button, the Odin's button that gives it. Kept as "touches" in reglages.ini; a change swaps two buttons.
// L2 and R2 stay: the game reads them as analog triggers too.
constexpr int kButtons = 10;
const char* const kOdinButtons[kButtons] = {"A", "B", "X", "Y", "L1", "R1", "L3", "R3", "START", "SELECT"};
const char* GameButton(int i) {
  const char* french[] = {"Croix", "Rond", "Carré", "Triangle", "L1", "R1", "L3", "R3", "Options (pause)",
                          "Pavé tactile"};
  const char* english[] = {"Cross", "Circle", "Square", "Triangle", "L1", "R1", "L3", "R3", "Options (pause)",
                           "Touchpad"};
  return L(french[i], english[i]);
}
constexpr int kControlsRows = kButtons + 1;  // and "back to the defaults"


class Menu {
 public:
  enum class Screen { kChoose, kMain, kSettings, kSaves, kControls, kDevice, kAbout };

  Menu(SDL_GPUDevice* gpu, std::string data_folder) : gpu_(gpu), data_(std::move(data_folder)),
                                                        settings_(data_ + "/reglages.ini") {
    name_ = settings_.Get("nom", "");
    std::snprintf(name_buffer_, sizeof(name_buffer_), "%s", name_.c_str());
  }

  ~Menu() { CloseGame(); }

  // The folder kept from last time: straight to the menu if it still checks out.
  void Start() {
    std::string language = settings_.Get("langue", "");
    if (language.empty()) {
      // The system's language the first time: English unless it's French.
      int count = 0;
      SDL_Locale** locales = SDL_GetPreferredLocales(&count);
      language = locales && count > 0 && std::strcmp(locales[0]->language, "fr") == 0 ? "fr" : "en";
      SDL_free(locales);
    }
    SetLanguage(language == "en");
    std::string kept = Call<std::string>("gameFolder");
    if (!kept.empty() && OpenGame(kept) && verdict_.ready) {
      screen_ = Screen::kMain;
    } else {
      screen_ = Screen::kChoose;
    }
  }

  void OnFolderPicked(const std::string& uri) {
    picked_uri_ = uri;
    checking_ = 2;  // drawn once with "Vérification…", then checked
  }

  void OnCommand(Command command);
  void Frame(ImVec2 size, float dt, const Fonts& fonts);

  // Back from the game: music again, unless the game still runs in the background.
  void OnForeground() {
    // What the game's screen had to say (it couldn't start, or stopped by itself): shown until tapped.
    std::string message = Call<std::string>("lastGameMessage");
    if (!message.empty()) error_ = message;
    if (!playing_) return;
    playing_ = false;
    game_running_ = Call<bool>("gameRunning");
    if (music_) music_->SetPaused(game_running_);
    Log("back from the game (still running: %d)", int(game_running_));
  }

 private:
  bool OpenGame(const std::string& uri) {
    CloseGame();
    std::string lines = Call<std::string>("openFolder", {uri});
    files_ = std::make_unique<GameFiles>(lines);
    Log("folder: %zu files", files_->count());
    Recheck();
    if (!verdict_.ready) return true;
    background_ = LoadPng(gpu_, files_->ReadAll("sce_sys/pic1.png"));
    picture_ = LoadPng(gpu_, files_->ReadAll("sce_sys/pic0.png"));
    music_ = std::make_unique<Music>(*files_);
    ApplyVolume();
    game_running_ = Call<bool>("gameRunning");
    music_->SetPaused(game_running_);
    return true;
  }

  // The check, in the menu's language.
  void Recheck() {
    verdict_ = files_ && files_->count()
                   ? Check(*files_)
                   : Verdict{false, L("Dossier illisible", "Unreadable folder"),
                             L("Android n'a pas pu lire ce dossier.", "Android couldn't read this folder."), ""};
  }

  void SetLanguage(bool english) {
    g_english = english;
    settings_.Set("langue", english ? "en" : "fr");
    Call<void>("setLanguage", {english ? "en" : "fr"});
    if (files_) Recheck();
    device_ = Call<std::string>("compatibility");  // in the new language
  }

  // The music reads the open files: it stops before Java closes them.
  void CloseGame() {
    music_.reset();
    background_ = {};
    picture_ = {};
    files_.reset();
  }

  void ApplyVolume() {
    if (music_) music_->SetVolume(float(settings_.GetInt("musique", 7)) / 10.0f);
  }

  // 30 or 40, from the chip and the screen; asked once.
  const std::string& DefaultFps() {
    if (default_fps_.empty()) default_fps_ = Call<std::string>("recommendedFps");
    if (default_fps_.empty()) default_fps_ = "30";
    return default_fps_;
  }

  std::vector<std::string> Drivers() {
    if (drivers_.empty()) {
      std::string all = Call<std::string>("drivers");
      size_t start = 0;
      while (start <= all.size()) {
        size_t comma = all.find(',', start);
        drivers_.push_back(all.substr(start, comma == std::string::npos ? std::string::npos : comma - start));
        if (comma == std::string::npos) break;
        start = comma + 1;
      }
    }
    return drivers_;
  }

  void Play() {
    if (busy_) return;
    if (music_) music_->SetPaused(true);
    std::string fps = settings_.Get("fps", DefaultFps()), msaa = settings_.Get("msaa", "1");
    std::string buttons;
    for (int o : Buttons()) buttons += std::string(buttons.empty() ? "" : ",") + kOdinButtons[o];
    Log("play: %s FPS, MSAA %s, buttons %s", fps.c_str(), msaa.c_str(), buttons.c_str());
    std::string driver = settings_.Get("pilote", "auto"), format = settings_.Get("format", "fit");
    Log("play: driver %s, picture %s", driver.c_str(), format.c_str());
    Call<void>("play", {fps, msaa, name_, buttons, driver, format});
    busy_ = true;
  }

  void DrawChoose(const Painter& p, ImVec2 size);
  void DrawMain(const Painter& p, ImVec2 size);
  void DrawSettings(const Painter& p, ImVec2 size);
  void DrawSaves(const Painter& p, ImVec2 size);
  void DrawControls(const Painter& p, ImVec2 size);
  void DrawAbout(const Painter& p, ImVec2 size);
  void DrawDevice(const Painter& p, ImVec2 size);
  // "OK", "PEUT" or "NON" (Compatibilite.java).
  std::string DeviceVerdict() const { return device_.substr(0, device_.find('\n')); }
  void DrawBottomBar(const Painter& p, ImVec2 size, const std::vector<std::pair<std::string, std::string>>& hints);
  void DrawPanel(const Painter& p, ImVec2 size, const char* title, ImVec2* body_min, ImVec2* body_max);
  void ChangeSetting(int row, int step);
  std::array<int, kButtons> Buttons() const;
  void SetButtons(const std::array<int, kButtons>& odin);
  void ChangeButton(int game, int step);

  SDL_GPUDevice* gpu_;
  std::string data_;
  Settings settings_;
  Screen screen_ = Screen::kChoose;
  std::unique_ptr<GameFiles> files_;
  Verdict verdict_;
  Texture background_, picture_;
  std::unique_ptr<Music> music_;

  std::string picked_uri_;
  int checking_ = 0;
  float ready_timer_ = 0;  // "Prêt à jouer" stays a moment before the menu
  float recheck_ = 0;
  float power_check_ = 0;
  bool charging_ = false;  // a hint about the battery on the main screen
  int main_focus_ = 0, settings_focus_ = 0, saves_focus_ = 0, controls_focus_ = 0;
  bool editing_name_ = false;
  std::string name_;
  char name_buffer_[17] = {};
  bool busy_ = false, playing_ = false, game_running_ = false;
  std::string error_;
  std::string device_;  // Compatibilite.java's report
  std::string default_fps_;
  bool sound_checked_ = false, sound_module_ = true;
  std::vector<std::string> drivers_;
};

const char* MainItem(int i) {
  const char* french[] = {"JOUER", "RÉGLAGES", "SAUVEGARDES", "COMMANDES", "APPAREIL", "À PROPOS"};
  const char* english[] = {"PLAY", "SETTINGS", "SAVES", "CONTROLS", "DEVICE", "ABOUT"};
  return L(french[i], english[i]);
}
constexpr int kMainItems = 6;
constexpr int kSettingsRows = 8;
constexpr int kSavesRows = 3;
const char* const kExchanges[kSavesRows] = {"exportSaves", "importSaves", "exportLog"};

std::array<int, kButtons> Menu::Buttons() const {
  std::array<int, kButtons> odin;
  for (int i = 0; i < kButtons; ++i) odin[size_t(i)] = i;
  std::string kept = settings_.Get("touches", "");
  std::array<int, kButtons> read{};
  int seen = 0, count = 0;
  size_t start = 0;
  while (count < kButtons && start <= kept.size() && !kept.empty()) {
    size_t comma = kept.find(',', start);
    std::string name = kept.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
    int found = -1;
    for (int o = 0; o < kButtons; ++o) {
      if (name == kOdinButtons[o]) found = o;
    }
    if (found < 0 || (seen & (1 << found))) return odin;  // damaged: the defaults
    seen |= 1 << found;
    read[size_t(count++)] = found;
    if (comma == std::string::npos) break;
    start = comma + 1;
  }
  return count == kButtons ? read : odin;
}

void Menu::SetButtons(const std::array<int, kButtons>& odin) {
  std::string text;
  for (int i = 0; i < kButtons; ++i) text += std::string(i ? "," : "") + kOdinButtons[odin[size_t(i)]];
  settings_.Set("touches", text);
}

// The PS4 button `game` gets the next or previous Odin button; the button that had it takes the old one.
void Menu::ChangeButton(int game, int step) {
  std::array<int, kButtons> odin = Buttons();
  int wanted = (odin[size_t(game)] + step + kButtons) % kButtons;
  for (int other = 0; other < kButtons; ++other) {
    if (odin[size_t(other)] == wanted) odin[size_t(other)] = odin[size_t(game)];
  }
  odin[size_t(game)] = wanted;
  SetButtons(odin);
}

void Menu::ChangeSetting(int row, int step) {
  if (row == 0) {
    const char* values[] = {"30", "40", "max"};
    std::string now = settings_.Get("fps", DefaultFps());
    int index = now == "30" ? 0 : now == "max" ? 2 : 1;
    settings_.Set("fps", values[std::clamp(index + step, 0, 2)]);
  } else if (row == 1) {
    settings_.Set("msaa", step > 0 ? "2" : "1");
  } else if (row == 2) {
    settings_.Set("musique", std::to_string(std::clamp(settings_.GetInt("musique", 7) + step, 0, 10)));
    ApplyVolume();
  } else if (row == 5) {
    SetLanguage(step > 0);
  } else if (row == 6) {
    std::vector<std::string> drivers = Drivers();
    std::string now = settings_.Get("pilote", "auto");
    int index = 0;
    for (int i = 0; i < int(drivers.size()); ++i) {
      if (drivers[size_t(i)] == now) index = i;
    }
    int count = std::max(1, int(drivers.size()));
    settings_.Set("pilote", drivers.empty() ? "auto" : drivers[size_t((index + step + count) % count)]);
  } else if (row == 7) {
    settings_.Set("format", step > 0 ? "stretch" : "fit");
  }
}

void Menu::OnCommand(Command command) {
  if (busy_ || checking_) return;
  if (command == Command::kSkipSong) {
    if (music_) music_->Skip();
    return;
  }
  switch (screen_) {
    case Screen::kChoose:
      if (command == Command::kAccept) Call<void>("pickFolder");
      if (command == Command::kBack && verdict_.ready) screen_ = Screen::kMain;
      break;
    case Screen::kMain:
      if (command == Command::kUp) main_focus_ = (main_focus_ + kMainItems - 1) % kMainItems;
      if (command == Command::kDown) main_focus_ = (main_focus_ + 1) % kMainItems;
      if (command == Command::kAccept) {
        if (main_focus_ == 0) Play();
        if (main_focus_ == 1) screen_ = Screen::kSettings;
        if (main_focus_ == 2) screen_ = Screen::kSaves;
        if (main_focus_ == 3) screen_ = Screen::kControls;
        if (main_focus_ == 4) screen_ = Screen::kDevice;
        if (main_focus_ == 5) screen_ = Screen::kAbout;
      }
      break;
    case Screen::kSaves:
      if (command == Command::kUp) saves_focus_ = (saves_focus_ + kSavesRows - 1) % kSavesRows;
      if (command == Command::kDown) saves_focus_ = (saves_focus_ + 1) % kSavesRows;
      if (command == Command::kAccept) Call<void>("exchange", {kExchanges[saves_focus_]});
      if (command == Command::kBack) screen_ = Screen::kMain;
      break;
    case Screen::kSettings:
      if (editing_name_) {
        if (command == Command::kBack || command == Command::kAccept) editing_name_ = false;
        break;
      }
      if (command == Command::kUp) settings_focus_ = (settings_focus_ + kSettingsRows - 1) % kSettingsRows;
      if (command == Command::kDown) settings_focus_ = (settings_focus_ + 1) % kSettingsRows;
      if (command == Command::kLeft) ChangeSetting(settings_focus_, -1);
      if (command == Command::kRight) ChangeSetting(settings_focus_, +1);
      if (command == Command::kAccept && settings_focus_ == 3) editing_name_ = true;
      if (command == Command::kAccept && settings_focus_ == 6) ChangeSetting(6, +1);
      if (command == Command::kAccept && settings_focus_ == 7) {
        ChangeSetting(7, settings_.Get("format", "fit") == "fit" ? +1 : -1);
      }
      if (command == Command::kAccept && settings_focus_ == 4) {
        screen_ = Screen::kChoose;
        Call<void>("pickFolder");
      }
      if (command == Command::kBack) screen_ = Screen::kMain;
      break;
    case Screen::kControls:
      if (command == Command::kUp) controls_focus_ = (controls_focus_ + kControlsRows - 1) % kControlsRows;
      if (command == Command::kDown) controls_focus_ = (controls_focus_ + 1) % kControlsRows;
      if (controls_focus_ < kButtons && (command == Command::kLeft || command == Command::kRight ||
                                         command == Command::kAccept)) {
        ChangeButton(controls_focus_, command == Command::kLeft ? -1 : +1);
      }
      if (controls_focus_ == kButtons && command == Command::kAccept) settings_.Set("touches", "");
      if (command == Command::kBack) screen_ = Screen::kMain;
      break;
    case Screen::kDevice:
    case Screen::kAbout:
      if (command == Command::kBack || command == Command::kAccept) screen_ = Screen::kMain;
      break;
  }
}

void Menu::DrawBottomBar(const Painter& p, ImVec2 size,
                         const std::vector<std::pair<std::string, std::string>>& hints) {
  float top = size.y - p.S(86);
  p.list->AddRectFilled(ImVec2(0, top), size, IM_COL32(0, 0, 0, 215));
  p.list->AddRectFilled(ImVec2(0, top), ImVec2(size.x, top + p.S(4)), kRed);
  float x = p.S(70), y = top + p.S(26);
  for (const auto& [button, label] : hints) {
    float r = p.S(19);
    p.list->AddCircleFilled(ImVec2(x + r, y + r), r, IM_COL32(255, 255, 255, 235));
    ImVec2 b = p.Measure(p.fonts.bold, 24, button);
    p.Text(p.fonts.bold, 24, ImVec2(x + r - b.x / 2, y + r - b.y / 2), IM_COL32(20, 20, 24, 255), button);
    p.Text(p.fonts.regular, 28, ImVec2(x + 2 * r + p.S(12), y + p.S(2)), kWhite, label);
    x += 2 * r + p.S(12) + p.Measure(p.fonts.regular, 28, label).x + p.S(48);
  }
  // Right: the game and the song.
  std::string right = verdict_.ready ? verdict_.detail : "";
  if (music_ && music_->playing() && settings_.GetInt("musique", 7) > 0 && !game_running_) {
    right = L("Musique du jeu, piste ", "Game music, track ") + std::to_string(music_->song() + 1) + "   ·   " + right;
  }
  ImVec2 r = p.Measure(p.fonts.regular, 24, right);
  p.Text(p.fonts.regular, 24, ImVec2(size.x - r.x - p.S(70), y + p.S(6)), kDim, right);
}

void Menu::DrawChoose(const Painter& p, ImVec2 size) {
  p.Cover(background_ ? background_ : Texture{}, size);
  p.list->AddRectFilledMultiColor(ImVec2(0, size.y * 0.45f), size, IM_COL32(0, 0, 0, 0), IM_COL32(0, 0, 0, 0),
                                  IM_COL32(0, 0, 0, 235), IM_COL32(0, 0, 0, 235));
  float centre = size.x / 2;
  if (!background_) {
    // No picture yet (first start): the name in the game's colours.
    ImVec2 a = p.Measure(p.fonts.bold, 150, "NBA "), b = p.Measure(p.fonts.bold, 150, "2K14");
    float x = centre - (a.x + b.x) / 2, y = p.S(160);
    p.Text(p.fonts.bold, 150, ImVec2(x, y), kWhite, "NBA ");
    p.Text(p.fonts.bold, 150, ImVec2(x + a.x, y), kRed, "2K14");
  }
  std::string intro = L("Choisis le dossier de ton jeu NBA 2K14 PS4 : celui qui contient eboot.bin et sce_sys.",
                        "Choose your NBA 2K14 PS4 game folder: the one that holds eboot.bin and sce_sys.");
  std::string status, detail;
  ImU32 status_colour = kWhite;
  if (checking_) {
    status = L("Vérification du dossier…", "Checking the folder…");
  } else if (!picked_uri_.empty() || !verdict_.headline.empty()) {
    status = verdict_.headline;
    detail = verdict_.detail;
    status_colour = verdict_.ready ? kGreen : IM_COL32(255, 120, 110, 255);
  }
  // First start on a device that may not run it: said before the player looks for the game.
  std::string verdict = DeviceVerdict();
  if (verdict == "NON" || verdict == "PEUT") {
    std::string warning = verdict == "NON"
                              ? L("Attention : cet appareil ne peut sans doute pas faire tourner le jeu (APPAREIL, "
                                  "dans le menu, dit pourquoi).",
                                  "Warning: this device likely can't run the game (DEVICE, in the menu, says why).")
                              : L("Cet appareil n'a jamais été essayé : le jeu peut tourner lentement, ou pas du tout.",
                                  "This device was never tried: the game may run slowly, or not at all.");
    ImVec2 w = p.Measure(p.fonts.bold, 30, warning, size.x * 0.8f);
    p.Text(p.fonts.bold, 30, ImVec2(centre - w.x / 2, p.S(50)), verdict == "NON" ? kSoftRed : kOrange, warning,
           size.x * 0.8f);
  }
  float y = size.y * 0.56f;
  ImVec2 m = p.Measure(p.fonts.regular, 34, intro);
  p.Text(p.fonts.regular, 34, ImVec2(centre - m.x / 2, y), kGrey, intro);
  // The one button.
  std::string label = verdict_.ready && !picked_uri_.empty() ? L("CHANGER DE DOSSIER", "CHANGE FOLDER")
                                                             : L("CHOISIR LE DOSSIER DU JEU", "CHOOSE THE GAME FOLDER");
  ImVec2 l = p.Measure(p.fonts.bold, 44, label);
  ImVec2 min(centre - l.x / 2 - p.S(60), y + p.S(80)), max(centre + l.x / 2 + p.S(60), y + p.S(80) + p.S(100));
  p.Slanted(min, max, kRed);
  p.Text(p.fonts.bold, 44, ImVec2(centre - l.x / 2 + p.S(12), min.y + (p.S(100) - l.y) / 2), kWhite, label);
  if (Tapped("choose", min, max)) OnCommand(Command::kAccept);
  if (!status.empty()) {
    ImVec2 s = p.Measure(p.fonts.bold, 48, status);
    p.Text(p.fonts.bold, 48, ImVec2(centre - s.x / 2, max.y + p.S(50)), status_colour, status);
    ImVec2 d = p.Measure(p.fonts.regular, 30, detail, size.x * 0.8f);
    p.Text(p.fonts.regular, 30, ImVec2(centre - d.x / 2, max.y + p.S(120)), kGrey, detail, size.x * 0.8f);
  }
}

void Menu::DrawMain(const Painter& p, ImVec2 size) {
  p.Cover(background_, size);
  p.list->AddRectFilledMultiColor(ImVec2(0, 0), ImVec2(size.x * 0.5f, size.y), IM_COL32(0, 0, 0, 225),
                                  IM_COL32(0, 0, 0, 0), IM_COL32(0, 0, 0, 0), IM_COL32(0, 0, 0, 225));
  // Under the cover's logo (it ends about 290 pixels down).
  float x = p.S(90), y = p.S(330), row = p.S(98);
  for (int i = 0; i < kMainItems; ++i) {
    std::string label = MainItem(i);
    if (i == 0 && game_running_) label = L("REPRENDRE", "RESUME");
    ImVec2 min(x - p.S(30), y + i * row), max(x + p.S(560), y + i * row + p.S(88));
    bool focused = i == main_focus_;
    if (focused) {
      p.Slanted(min, max, kRed);
    } else {
      p.Slanted(min, max, IM_COL32(0, 0, 0, 120));
    }
    ImVec2 t = p.Measure(p.fonts.bold, 58, label);
    p.Text(p.fonts.bold, 58, ImVec2(x + p.S(10), min.y + (p.S(88) - t.y) / 2), focused ? kWhite : kGrey, label);
    std::string id = "main" + std::to_string(i);
    if (Tapped(id.c_str(), min, max)) {
      main_focus_ = i;
      OnCommand(Command::kAccept);
    }
  }
  if (!sound_checked_) {
    sound_checked_ = true;
    sound_module_ = Call<bool>("soundModule");
  }
  if (!sound_module_) {
    p.Text(p.fonts.regular, 26, ImVec2(x + p.S(600), y + p.S(140)), kOrange,
           L("Pas de son : mets libSceNgs2.sprx, le module audio de ta PS4, dans le dossier du jeu.",
             "No sound: put libSceNgs2.sprx, your PS4's audio module, in the game folder."),
           p.S(640));
  }
  std::string verdict = DeviceVerdict();
  if (verdict == "NON" || verdict == "PEUT") {
    // Beside the menu, where the cover's picture leaves room.
    std::string warning = verdict == "NON"
                              ? L("Cet appareil ne peut sans doute pas faire tourner le jeu : voir APPAREIL.",
                                  "This device likely can't run the game: see DEVICE.")
                              : L("Cet appareil n'a jamais été essayé : voir APPAREIL.",
                                  "This device was never tried: see DEVICE.");
    // On a dark panel: over the cover's picture, small red text was hard to read on a phone.
    ImVec2 w = p.Measure(p.fonts.bold, 32, warning, p.S(640));
    ImVec2 wmin(x + p.S(590), y), wmax(x + p.S(590) + w.x + p.S(40), y + w.y + p.S(24));
    p.list->AddRectFilled(wmin, wmax, IM_COL32(0, 0, 0, 200), p.S(6));
    p.Text(p.fonts.bold, 32, ImVec2(wmin.x + p.S(20), wmin.y + p.S(12)), verdict == "NON" ? kSoftRed : kOrange,
           warning, p.S(640));
    if (Tapped("device-warning", wmin, wmax)) screen_ = Screen::kDevice;
  }
  if (charging_ && settings_.Get("fps", DefaultFps()) != "30") {
    p.Text(p.fonts.regular, 28, ImVec2(x - p.S(30), y + kMainItems * row + p.S(10)), kGrey,
           L("En charge : jouer chauffe la batterie et l'use plus vite. 30 images/s dans Réglages la ménage.",
             "Charging: playing heats the battery and wears it faster. 30 FPS in Settings spares it."),
           p.S(760));
  }
}

void Menu::DrawPanel(const Painter& p, ImVec2 size, const char* title, ImVec2* body_min, ImVec2* body_max) {
  p.Cover(picture_ ? picture_ : background_, size);
  ImVec2 min(p.S(90), p.S(90)), max(size.x - p.S(90), size.y - p.S(130));
  p.list->AddRectFilled(min, max, IM_COL32(8, 8, 12, 225), p.S(6));
  p.list->AddRectFilled(min, ImVec2(max.x, min.y + p.S(8)), kRed, p.S(6), ImDrawFlags_RoundCornersTop);
  p.Text(p.fonts.bold, 56, ImVec2(min.x + p.S(50), min.y + p.S(36)), kWhite, title);
  *body_min = ImVec2(min.x + p.S(50), min.y + p.S(130));
  *body_max = ImVec2(max.x - p.S(50), max.y - p.S(30));
}

void Menu::DrawSettings(const Painter& p, ImVec2 size) {
  ImVec2 min, max;
  DrawPanel(p, size, MainItem(1), &min, &max);
  std::string fps = settings_.Get("fps", DefaultFps());
  int music = settings_.GetInt("musique", 7);
  struct Row {
    const char* title;
    std::vector<std::string> choices;
    int selected;
    std::string hint;
  };
  int fps_index = fps == "30" ? 0 : fps == "max" ? 2 : 1;
  std::string fps_hints[] = {L("Le plus stable, et le moins chaud.", "The steadiest, and the coolest."),
                             L("Plus fluide, si l'appareil suit.", "More fluid, if the device keeps up."),
                             L("Plus fluide par moments, moins régulier.", "Smoother at times, less even.")};
  if (DefaultFps() == "30") fps_hints[0] += L(" Conseillé sur cet appareil.", " Recommended on this device.");
  if (DefaultFps() == "40") fps_hints[1] += L(" Conseillé sur cet appareil.", " Recommended on this device.");
  int hz = std::atoi(Call<std::string>("screenHz").c_str());
  if (hz > 0 && hz < 119) {
    fps_hints[1] = L("Cet écran (", "This screen (") + std::to_string(hz) +
                   L(" Hz) ne l'affiche pas régulièrement : 30 est plus fluide.", " Hz) can't show it evenly: 30 is smoother.");
  }
  bool msaa2 = settings_.Get("msaa", "1") == "2";
  std::vector<Row> rows = {
      {L("Images par seconde", "Frames per second"), {"30", "40", "MAX"}, fps_index, fps_hints[fps_index]},
      {L("Lissage des contours", "Anti-aliasing"), {L("COUPÉ", "OFF"), "2×"}, msaa2 ? 1 : 0,
       msaa2 ? L("Plus lisse, mais moins fluide et plus chaud.", "Smoother edges, but less fluid and hotter.")
             : L("Le plus fluide, conseillé.", "The most fluid, recommended.")},
      {L("Musique du menu", "Menu music"), {music == 0 ? L("COUPÉE", "OFF") : std::to_string(music * 10) + " %"}, 0,
       L("Gauche et droite pour régler, Y pour passer au morceau suivant.",
         "Left and right to set it, Y for the next song.")},
      {L("Nom du joueur", "Player name"), {name_.empty() ? "—" : name_}, 0,
       L("Le nom des nouveaux profils et de ta sauvegarde (16 lettres ou chiffres au plus).",
         "The name of new profiles and of your save (16 letters or digits at most).")},
      {L("Dossier du jeu", "Game folder"), {Call<std::string>("gameName")}, 0,
       L("A pour choisir un autre dossier.", "A to choose another folder.")},
      {L("Langue", "Language"), {"FRANÇAIS", "ENGLISH"}, g_english ? 1 : 0,
       L("La langue de ce menu. Le jeu reste en anglais.", "This menu's language. The game stays in English.")},
      {L("Pilote graphique", "Graphics driver"), {Call<std::string>("driverName", {settings_.Get("pilote", "auto")})}, 0,
       L("Auto choisit selon la puce. Si le jeu ne démarre pas ou que l'image est abîmée, essaie le suivant.",
         "Auto picks by chip. If the game doesn't start or the picture is damaged, try the next one.")},
      {L("Format de l'image", "Picture format"), {L("BANDES NOIRES", "BLACK BARS"), L("PLEIN ÉCRAN", "FULL SCREEN")},
       settings_.Get("format", "fit") == "stretch" ? 1 : 0,
       L("Plein écran étire l'image du jeu (16:9) sur les écrans plus allongés.",
         "Full screen stretches the game's 16:9 picture on longer screens.")},
  };
  float row_height = p.S(88);  // eight rows in the panel
  for (int i = 0; i < int(rows.size()); ++i) {
    const Row& row = rows[size_t(i)];
    float y = min.y + i * row_height;
    bool focused = i == settings_focus_;
    if (focused) p.list->AddRectFilled(ImVec2(min.x - p.S(20), y - p.S(6)), ImVec2(max.x + p.S(20), y + p.S(82)),
                                       IM_COL32(227, 27, 35, 70), p.S(4));
    p.Text(p.fonts.bold, 34, ImVec2(min.x, y), focused ? kWhite : kGrey, row.title);
    p.Text(p.fonts.regular, 24, ImVec2(min.x, y + p.S(44)), kDim, row.hint);
    // The choices on the right; a tap picks one (or edits the row).
    float right = max.x;
    for (int c = int(row.choices.size()) - 1; c >= 0; --c) {
      const std::string& choice = row.choices[size_t(c)];
      ImVec2 t = p.Measure(p.fonts.bold, 34, choice);
      ImVec2 cmin(right - t.x - p.S(56), y + p.S(6)), cmax(right, y + p.S(72));
      if (row.choices.size() == 1) cmin.x = std::min(cmin.x, right - p.S(420));
      bool on = c == row.selected;
      p.Slanted(cmin, cmax, on ? kRed : IM_COL32(60, 60, 66, 230));
      float text_x = row.choices.size() == 1 ? (cmin.x + cmax.x - t.x) / 2 : cmin.x + p.S(30);
      p.Text(p.fonts.bold, 34, ImVec2(text_x, cmin.y + (p.S(66) - t.y) / 2), kWhite, choice);
      std::string id = "set" + std::to_string(i) + "_" + std::to_string(c);
      if (Tapped(id.c_str(), cmin, cmax)) {
        settings_focus_ = i;
        if (i == 0) ChangeSetting(0, c - row.selected);
        if (i == 1) ChangeSetting(1, c == 1 ? 1 : -1);
        if (i == 2) ChangeSetting(2, +1 - (music >= 10 ? 11 : 0));
        if (i == 3) editing_name_ = true;
        if (i == 4) OnCommand(Command::kAccept);
        if (i == 5) ChangeSetting(5, c == 1 ? 1 : -1);
        if (i == 6) ChangeSetting(6, +1);
        if (i == 7) ChangeSetting(7, c == 1 ? 1 : -1);
      }
      right = cmin.x - p.S(14);
    }
  }
  if (editing_name_) {
    // Android's keyboard, through ImGui's text field.
    ImGui::SetCursorScreenPos(ImVec2(min.x, min.y + 3 * row_height + p.S(70)));
    ImGui::PushFont(p.fonts.regular, p.S(34));
    ImGui::SetNextItemWidth(p.S(600));
    ImGui::SetKeyboardFocusHere();
    bool done = ImGui::InputText("##nom", name_buffer_, sizeof(name_buffer_),
                                 ImGuiInputTextFlags_EnterReturnsTrue | ImGuiInputTextFlags_CharsNoBlank);
    ImGui::PopFont();
    if (done) editing_name_ = false;
    std::string typed;
    for (const char* c = name_buffer_; *c; ++c) {
      if (std::isalnum(static_cast<unsigned char>(*c)) || *c == '_' || *c == '-') typed += *c;
    }
    if (typed != name_) {
      name_ = typed;
      settings_.Set("nom", name_);
    }
  }
}

void Menu::DrawSaves(const Painter& p, ImVec2 size) {
  ImVec2 min, max;
  DrawPanel(p, size, MainItem(2), &min, &max);
  const std::pair<const char*, const char*> rows[kSavesRows] = {
      {L("Exporter les sauvegardes", "Export the saves"),
       L("Un fichier .zip à l'endroit de ton choix, par exemple Download. À faire avant de désinstaller l'app.",
         "A .zip file wherever you choose, Download for example. Do it before uninstalling the app.")},
      {L("Importer des sauvegardes", "Import saves"),
       L("Un .zip exporté par cette app. Tes sauvegardes actuelles sont d'abord mises de côté, rien n'est effacé.",
         "A .zip exported by this app. Your current saves are set aside first, nothing is erased.")},
      {L("Exporter le journal", "Export the log"),
       L("Pour signaler un problème : un .zip avec les journaux du jeu et du menu, à envoyer.",
         "To report a problem: a .zip of the game's and the menu's logs, to send.")},
  };
  float row_height = p.S(118);
  for (int i = 0; i < kSavesRows; ++i) {
    float y = min.y + i * row_height;
    bool focused = i == saves_focus_;
    ImVec2 rmin(min.x - p.S(20), y - p.S(8)), rmax(max.x + p.S(20), y + p.S(100));
    if (focused) p.list->AddRectFilled(rmin, rmax, IM_COL32(227, 27, 35, 70), p.S(4));
    p.Text(p.fonts.bold, 38, ImVec2(min.x, y), focused ? kWhite : kGrey, rows[i].first);
    p.Text(p.fonts.regular, 26, ImVec2(min.x, y + p.S(50)), kDim, rows[i].second, max.x - min.x);
    std::string id = "saves" + std::to_string(i);
    if (Tapped(id.c_str(), rmin, rmax)) {
      saves_focus_ = i;
      OnCommand(Command::kAccept);
    }
  }
  float y = min.y + kSavesRows * row_height + p.S(30);
  std::string state = Call<std::string>("exchangeState");
  if (!state.empty()) {
    bool failed = state[0] == '!';
    p.Text(p.fonts.bold, 32, ImVec2(min.x, y), failed ? IM_COL32(255, 120, 110, 255) : kGreen,
           failed ? state.substr(1) : state, max.x - min.x);
    y += p.S(90);
  }
  p.Text(p.fonts.regular, 26, ImVec2(min.x, y), kDim,
         L("Les sauvegardes sont dans le dossier de l'app (", "The saves are in the app's folder (") + data_ +
             L("). Android les efface si tu désinstalles l'app, pas lors d'une mise à jour.",
               "). Android erases them if you uninstall the app, not when it updates."),
         max.x - min.x);
}

void Menu::DrawControls(const Painter& p, ImVec2 size) {
  ImVec2 min, max;
  DrawPanel(p, size, MainItem(3), &min, &max);
  // Left: the buttons the player can change, a row per PS4 button. Right: what stays.
  float left_width = p.S(780), right = min.x + p.S(880);
  p.Text(p.fonts.bold, 30, ImVec2(min.x, min.y - p.S(10)), kRed, L("DANS LE JEU (PS4)", "IN THE GAME (PS4)"));
  p.Text(p.fonts.bold, 30, ImVec2(min.x + p.S(430), min.y - p.S(10)), kRed, L("TOUCHE DE LA MANETTE", "GAMEPAD BUTTON"));
  std::array<int, kButtons> odin = Buttons();
  float row_height = p.S(56);
  for (int i = 0; i < kControlsRows; ++i) {
    float y = min.y + p.S(44) + i * row_height;
    bool focused = i == controls_focus_;
    ImVec2 rmin(min.x - p.S(16), y - p.S(4)), rmax(min.x + left_width, y + p.S(50));
    if (focused) p.list->AddRectFilled(rmin, rmax, IM_COL32(227, 27, 35, 70), p.S(4));
    if (i < kButtons) {
      p.Text(p.fonts.regular, 32, ImVec2(min.x, y), focused ? kWhite : kGrey, GameButton(i));
      const char* button = kOdinButtons[odin[size_t(i)]];
      bool changed = odin[size_t(i)] != i;
      ImVec2 t = p.Measure(p.fonts.bold, 30, button);
      ImVec2 cmin(min.x + p.S(430), y), cmax(min.x + p.S(430) + t.x + p.S(56), y + p.S(44));
      p.Slanted(cmin, cmax, changed ? kRed : IM_COL32(60, 60, 66, 230));
      p.Text(p.fonts.bold, 30, ImVec2(cmin.x + p.S(28), cmin.y + (p.S(44) - t.y) / 2), kWhite, button);
    } else {
      p.Text(p.fonts.bold, 32, ImVec2(min.x, y), focused ? kWhite : kGrey,
             L("Rétablir les touches d'origine", "Back to the original buttons"));
    }
    std::string id = "button" + std::to_string(i);
    if (Tapped(id.c_str(), rmin, rmax)) {
      controls_focus_ = i;
      OnCommand(Command::kAccept);
    }
  }
  const std::pair<const char*, const char*> fixed[] = {
      {"L2  /  R2", L("L2  /  R2 (gâchettes)", "L2  /  R2 (triggers)")},
      {"Sticks", L("Sticks gauche et droit", "Left and right sticks")},
      {L("Croix directionnelle", "D-pad"), L("Croix directionnelle", "D-pad")},
      {L("Select + Start maintenus 2 s", "Select + Start held for 2 s"),
       L("Quitter le jeu et revenir à ce menu", "Quit the game and come back to this menu")},
      {L("Touche en bas à droite, L3 + R3 ou Retour", "Bottom right key, L3 + R3 or Back"),
       L("Menu de l'app : reprendre, infos, retour au menu, quitter", "App menu: resume, info, back to menu, quit")},
  };
  p.Text(p.fonts.bold, 30, ImVec2(right, min.y - p.S(10)), kRed, L("TOUJOURS", "ALWAYS"));
  float y = min.y + p.S(44);
  for (const auto& [button, what] : fixed) {
    p.Text(p.fonts.bold, 30, ImVec2(right, y), kWhite, button, max.x - right);
    p.Text(p.fonts.regular, 28, ImVec2(right, y + p.S(40)), kGrey, what, max.x - right);
    y += p.S(108);
  }
  p.Text(p.fonts.regular, 24, ImVec2(right, y + p.S(10)), kDim,
         L("Gauche et droite changent la touche ; celle qui l'avait prend l'ancienne. Gardé pour les prochaines "
           "parties.",
           "Left and right change the button; the one that had it takes the old one. Kept for the next games."),
         max.x - right);
}

void Menu::DrawAbout(const Painter& p, ImVec2 size) {
  ImVec2 min, max;
  DrawPanel(p, size, MainItem(5), &min, &max);
  float wrap = max.x - min.x;
  std::string lines[] = {
      L("NBA 2K14 pour Android, version ", "NBA 2K14 for Android, version ") + Call<std::string>("version"),
      L("Ton jeu est lu directement dans ton dossier « ", "Your game is read straight from your folder “") +
          Call<std::string>("gameName") +
          L(" » : rien n'est copié dans l'app. ", "”: nothing is copied into the app. ") + verdict_.detail + ".",
      L("Moteur : shadPS4 (GPL-2.0), avec FEX (MIT) et les pilotes Turnip et PanVK de Mesa (MIT), dans les versions "
        "de Bachata S4.",
        "Engine: shadPS4 (GPL-2.0), with FEX (MIT) and Mesa's Turnip and PanVK drivers (MIT), as Bachata S4 ships them."),
      L("Menu : SDL3 (zlib), Dear ImGui (MIT), LibAtrac9 (MIT), stb_image (domaine public), police Oswald (SIL OFL "
        "1.1). Image et musique : celles de ton jeu.",
        "Menu: SDL3 (zlib), Dear ImGui (MIT), LibAtrac9 (MIT), stb_image (public domain), Oswald font (SIL OFL 1.1). "
        "Picture and music: your game's."),
      L("Réglages, journaux et sauvegardes : ", "Settings, logs and saves: ") + data_,
      L("NBA 2K14 © 2K Sports. App personnelle et non officielle : elle ne contient pas le jeu, et le jeu ne se partage "
        "pas.",
        "NBA 2K14 © 2K Sports. Personal, unofficial app: it doesn't contain the game, and the game isn't to be shared."),
  };
  float y = min.y;
  for (size_t i = 0; i < std::size(lines); ++i) {
    ImFont* font = i == 0 ? p.fonts.bold : p.fonts.regular;
    float text_size = i == 0 ? 40 : 30;
    p.Text(font, text_size, ImVec2(min.x, y), i == 0 ? kWhite : kGrey, lines[i], wrap);
    y += p.Measure(font, text_size, lines[i], wrap).y + p.S(30);
  }
}

// What the device has against what the engine needs: a line per check, green, orange or red.
void Menu::DrawDevice(const Painter& p, ImVec2 size) {
  ImVec2 min, max;
  DrawPanel(p, size, MainItem(4), &min, &max);
  float wrap = max.x - min.x - p.S(380);
  std::string verdict = DeviceVerdict();
  std::string headline = verdict == "OK"     ? L("Cet appareil devrait faire tourner le jeu.",
                                                 "This device should run the game.")
                         : verdict == "PEUT" ? L("Peut-être : cet appareil n'a jamais été essayé.",
                                                 "Maybe: this device was never tried.")
                                             : L("Cet appareil ne peut sans doute pas faire tourner le jeu.",
                                                 "This device likely can't run the game.");
  ImU32 colour = verdict == "OK" ? kGreen : verdict == "PEUT" ? kOrange : kSoftRed;
  p.Text(p.fonts.bold, 40, ImVec2(min.x, min.y - p.S(10)), colour, headline, max.x - min.x);
  float y = min.y + p.S(70);
  size_t start = device_.find('\n');
  while (start != std::string::npos && start + 1 < device_.size()) {
    size_t end = device_.find('\n', start + 1);
    std::string line = device_.substr(start + 1, end == std::string::npos ? std::string::npos : end - start - 1);
    start = end;
    size_t a = line.find('\t'), b = a == std::string::npos ? a : line.find('\t', a + 1);
    if (b == std::string::npos) continue;
    char mark = line[0];
    std::string what = line.substr(a + 1, b - a - 1), detail = line.substr(b + 1);
    ImU32 dot = mark == '+' ? kGreen : mark == '?' ? kOrange : kSoftRed;
    p.list->AddCircleFilled(ImVec2(min.x + p.S(12), y + p.S(20)), p.S(11), dot);
    p.Text(p.fonts.bold, 32, ImVec2(min.x + p.S(40), y), kWhite, what);
    p.Text(p.fonts.regular, 30, ImVec2(min.x + p.S(380), y + p.S(2)), kGrey, detail, wrap);
    y += std::max(p.S(58), p.Measure(p.fonts.regular, 30, detail, wrap).y + p.S(22));
  }
  p.Text(p.fonts.regular, 26, ImVec2(min.x, y + p.S(16)), kDim,
         L("Seul l'Odin 3 (Snapdragon 8 Elite, 12 Go) a été essayé. Pour signaler comment le jeu tourne ici : "
           "SAUVEGARDES, puis Exporter le journal (ce rapport y est joint).",
           "Only the Odin 3 (Snapdragon 8 Elite, 12 GB) was tried. To report how the game runs here: SAVES, then "
           "Export the log (this report is in it)."),
         max.x - min.x);
}

void Menu::Frame(ImVec2 size, float dt, const Fonts& fonts) {
  ImGui::SetNextWindowPos(ImVec2(0, 0));
  ImGui::SetNextWindowSize(size);
  ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0, 0));
  ImGui::Begin("menu", nullptr,
               ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoBackground | ImGuiWindowFlags_NoMove |
                   ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoBringToFrontOnFocus);
  Painter p{ImGui::GetWindowDrawList(), size.y / 1080.0f, fonts};

  if (checking_ && --checking_ == 0) {
    OpenGame(picked_uri_);
    if (verdict_.ready) {
      Call<void>("keepFolder", {picked_uri_});
      sound_checked_ = false;
      ready_timer_ = 1.6f;
    }
  }
  if (ready_timer_ > 0 && (ready_timer_ -= dt) <= 0) {
    screen_ = Screen::kMain;
    picked_uri_.clear();
  }

  // A game left running in the background may end there: Play, and the music, come back.
  if (game_running_ && !playing_ && (recheck_ -= dt) <= 0) {
    recheck_ = 2.0f;
    game_running_ = Call<bool>("gameRunning");
    if (!game_running_ && music_) music_->SetPaused(false);
  }

  // Plugged in: a hot game while charging wears the battery faster. Looked at every few seconds.
  if ((power_check_ -= dt) <= 0) {
    power_check_ = 5.0f;
    SDL_PowerState power = SDL_GetPowerInfo(nullptr, nullptr);
    charging_ = power == SDL_POWERSTATE_CHARGING || power == SDL_POWERSTATE_CHARGED;
  }

  // Play: Java prepares, then starts the game.
  if (busy_) {
    std::string state = Call<std::string>("state");
    if (state.empty()) {
      busy_ = false;
      playing_ = true;
      error_.clear();
    } else if (state[0] == '!') {
      busy_ = false;
      error_ = state.substr(1);
      if (music_) music_->SetPaused(false);
    }
  }

  std::vector<std::pair<std::string, std::string>> hints;
  switch (screen_) {
    case Screen::kChoose:
      DrawChoose(p, size);
      hints = {{"A", L("Choisir le dossier", "Choose the folder")}};
      if (verdict_.ready && picked_uri_.empty()) hints.push_back({"B", L("Retour", "Back")});
      break;
    case Screen::kMain:
      DrawMain(p, size);
      hints = {{"A", L("Choisir", "Select")}, {"Y", L("Morceau suivant", "Next song")}};
      break;
    case Screen::kSettings:
      DrawSettings(p, size);
      hints = {{"A", L("Modifier", "Change")}, {"B", L("Retour", "Back")}, {"Y", L("Morceau suivant", "Next song")}};
      break;
    case Screen::kSaves:
      DrawSaves(p, size);
      hints = {{"A", L("Choisir", "Select")}, {"B", L("Retour", "Back")}};
      break;
    case Screen::kControls:
      DrawControls(p, size);
      hints = {{"A", L("Changer la touche", "Change the button")}, {"B", L("Retour", "Back")}};
      break;
    case Screen::kDevice:
      DrawDevice(p, size);
      hints = {{"B", L("Retour", "Back")}};
      break;
    case Screen::kAbout:
      DrawAbout(p, size);
      hints = {{"B", L("Retour", "Back")}};
      break;
  }
  if (screen_ != Screen::kChoose) DrawBottomBar(p, size, hints);

  // Preparation or an error, over everything.
  std::string overlay = busy_ ? Call<std::string>("state") : error_;
  if (!overlay.empty()) {
    ImVec2 t = p.Measure(fonts.bold, 40, overlay, size.x * 0.7f);
    ImVec2 min(size.x / 2 - t.x / 2 - p.S(50), size.y / 2 - t.y / 2 - p.S(40));
    ImVec2 max(size.x / 2 + t.x / 2 + p.S(50), size.y / 2 + t.y / 2 + p.S(40));
    p.list->AddRectFilled(min, max, IM_COL32(0, 0, 0, 230), p.S(6));
    p.list->AddRect(min, max, busy_ ? kRed : IM_COL32(255, 120, 110, 255), p.S(6), 0, p.S(3));
    p.Text(fonts.bold, 40, ImVec2(min.x + p.S(50), min.y + p.S(40)), kWhite, overlay, size.x * 0.7f);
    if (!busy_ && Tapped("error", min, max)) error_.clear();
  }
  ImGui::End();
  ImGui::PopStyleVar();
}

// ------------------------------------------------------------------ controller

// Controller buttons as menu commands: d-pad or left stick to move, with repeat when held; South (A) selects,
// East (B) goes back, North (Y) skips the song.
class Controller {
 public:
  void Poll(float dt, Menu& menu) {
    enum : uint32_t { kUp = 1, kDown = 2, kLeft = 4, kRight = 8, kSouth = 16, kEast = 32, kNorth = 64 };
    uint32_t buttons = 0;
    int count = 0;
    SDL_JoystickID* ids = SDL_GetGamepads(&count);
    for (int i = 0; i < count; ++i) {
      SDL_Gamepad* pad = SDL_GetGamepadFromID(ids[i]);
      if (!pad) pad = SDL_OpenGamepad(ids[i]);
      if (!pad) continue;
      if (SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_DPAD_UP)) buttons |= kUp;
      if (SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_DPAD_DOWN)) buttons |= kDown;
      if (SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_DPAD_LEFT)) buttons |= kLeft;
      if (SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_DPAD_RIGHT)) buttons |= kRight;
      if (SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_SOUTH)) buttons |= kSouth;
      if (SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_EAST)) buttons |= kEast;
      if (SDL_GetGamepadButton(pad, SDL_GAMEPAD_BUTTON_NORTH)) buttons |= kNorth;
      const Sint16 dead = 16000;
      Sint16 x = SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFTX);
      Sint16 y = SDL_GetGamepadAxis(pad, SDL_GAMEPAD_AXIS_LEFTY);
      if (y < -dead) buttons |= kUp;
      if (y > dead) buttons |= kDown;
      if (x < -dead) buttons |= kLeft;
      if (x > dead) buttons |= kRight;
    }
    SDL_free(ids);
    uint32_t pressed = buttons & ~previous_;
    const uint32_t directions = kUp | kDown | kLeft | kRight;
    if (buttons & directions & previous_) {
      repeat_ -= dt;
      if (repeat_ <= 0) {
        pressed |= buttons & directions;
        repeat_ = 0.12f;
      }
    } else {
      repeat_ = 0.4f;
    }
    previous_ = buttons;
    if (pressed & kUp) menu.OnCommand(Command::kUp);
    if (pressed & kDown) menu.OnCommand(Command::kDown);
    if (pressed & kLeft) menu.OnCommand(Command::kLeft);
    if (pressed & kRight) menu.OnCommand(Command::kRight);
    if (pressed & kSouth) menu.OnCommand(Command::kAccept);
    if (pressed & kEast) menu.OnCommand(Command::kBack);
    if (pressed & kNorth) menu.OnCommand(Command::kSkipSong);
  }

 private:
  uint32_t previous_ = 0;
  float repeat_ = 0;
};

}  // namespace

// The folder picker's answer (Accueil.onActivityResult).
extern "C" JNIEXPORT void JNICALL Java_com_nba2k14_lanceur_Accueil_nativeFolderPicked(JNIEnv* env, jclass,
                                                                                     jstring uri) {
  const char* chars = env->GetStringUTFChars(uri, nullptr);
  std::lock_guard lock(g_picked_mutex);
  g_picked = std::string(chars);
  env->ReleaseStringUTFChars(uri, chars);
}

int main(int, char*[]) {
  SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
  if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_GAMEPAD)) return 1;

  std::string data_folder = Call<std::string>("dataFolder");
  g_log = std::fopen((data_folder + "/lanceur.log").c_str(), "w");
  Log("data: %s", data_folder.c_str());

  SDL_Window* window = SDL_CreateWindow("NBA 2K14", 0, 0, SDL_WINDOW_FULLSCREEN);
  SDL_GPUDevice* gpu = window ? SDL_CreateGPUDevice(SDL_GPU_SHADERFORMAT_SPIRV, false, nullptr) : nullptr;
  if (!gpu || !SDL_ClaimWindowForGPUDevice(gpu, window)) {
    Log("no window or GPU device: %s", SDL_GetError());
    return 1;
  }
  SDL_SetGPUSwapchainParameters(gpu, window, SDL_GPU_SWAPCHAINCOMPOSITION_SDR, SDL_GPU_PRESENTMODE_VSYNC);
  Log("GPU: %s", SDL_GetGPUDeviceDriver(gpu));

  ImGui::CreateContext();
  ImGuiIO& io = ImGui::GetIO();
  io.IniFilename = nullptr;
  io.LogFilename = nullptr;
  ImGui_ImplSDL3_InitForSDLGPU(window);
  ImGui_ImplSDLGPU3_InitInfo init_info;
  init_info.Device = gpu;
  init_info.ColorTargetFormat = SDL_GetGPUSwapchainTextureFormat(gpu, window);
  ImGui_ImplSDLGPU3_Init(&init_info);
  // Oswald (SIL Open Font License, assets/polices/OFL.txt), close to the game's condensed letters: the game keeps
  // its own as pictures, which can't be read. The system's fonts if it's missing.
  Fonts fonts;
  auto asset_font = [&](const char* path) -> ImFont* {
    size_t size = 0;
    void* data = SDL_LoadFile(path, &size);  // from the APK's assets; kept for the app's life
    if (!data) return nullptr;
    ImFontConfig config;
    config.FontDataOwnedByAtlas = false;
    return io.Fonts->AddFontFromMemoryTTF(data, int(size), 0.0f, &config);
  };
  fonts.regular = asset_font("polices/Oswald-Regular.ttf");
  fonts.bold = asset_font("polices/Oswald-Bold.ttf");
  if (!fonts.regular || !fonts.bold) {
    Log("Oswald missing: %s", SDL_GetError());
    fonts.regular = io.Fonts->AddFontFromFileTTF("/system/fonts/Roboto-Regular.ttf");
    fonts.bold = io.Fonts->AddFontFromFileTTF("/system/fonts/DroidSans-Bold.ttf");
    fonts.fake_bold = true;
  }
  if (!fonts.regular) fonts.regular = io.Fonts->AddFontDefault();
  if (!fonts.bold) fonts.bold = fonts.regular;
  ImGui::GetStyle().Colors[ImGuiCol_FrameBg] = ImVec4(0.1f, 0.1f, 0.12f, 0.95f);

  // Its textures go before the GPU device.
  auto menu_owner = std::make_unique<Menu>(gpu, data_folder);
  Menu& menu = *menu_owner;
  menu.Start();
  Controller controller;
  bool running = true;
  auto last = std::chrono::steady_clock::now();
  while (running) {
    SDL_Event event;
    while (SDL_PollEvent(&event)) {
      ImGui_ImplSDL3_ProcessEvent(&event);
      if (event.type == SDL_EVENT_QUIT || event.type == SDL_EVENT_TERMINATING) running = false;
      // Back, and a d-pad or keyboard that Android reports as keys rather than as a gamepad.
      if (event.type == SDL_EVENT_KEY_DOWN && !ImGui::GetIO().WantTextInput) {
        switch (event.key.scancode) {
          case SDL_SCANCODE_AC_BACK:
          case SDL_SCANCODE_ESCAPE: menu.OnCommand(Command::kBack); break;
          case SDL_SCANCODE_UP: menu.OnCommand(Command::kUp); break;
          case SDL_SCANCODE_DOWN: menu.OnCommand(Command::kDown); break;
          case SDL_SCANCODE_LEFT: menu.OnCommand(Command::kLeft); break;
          case SDL_SCANCODE_RIGHT: menu.OnCommand(Command::kRight); break;
          case SDL_SCANCODE_RETURN:
          case SDL_SCANCODE_SELECT: menu.OnCommand(Command::kAccept); break;
          default: break;
        }
      }
      if (event.type == SDL_EVENT_DID_ENTER_FOREGROUND || event.type == SDL_EVENT_WINDOW_FOCUS_GAINED) {
        for (int button = 0; button < ImGuiMouseButton_COUNT; ++button) io.AddMouseButtonEvent(button, false);
        menu.OnForeground();
        last = std::chrono::steady_clock::now();
      }
    }
    if (!running) break;
    {
      std::lock_guard lock(g_picked_mutex);
      if (g_picked) {
        menu.OnFolderPicked(*g_picked);
        g_picked.reset();
      }
    }

    auto now = std::chrono::steady_clock::now();
    float dt = std::min(std::chrono::duration<float>(now - last).count(), 0.1f);
    last = now;
    controller.Poll(dt, menu);

    ImGui_ImplSDLGPU3_NewFrame();
    ImGui_ImplSDL3_NewFrame();
    ImGui::NewFrame();
    menu.Frame(io.DisplaySize, dt, fonts);
    ImGui::Render();
    ImDrawData* draw_data = ImGui::GetDrawData();
    SDL_GPUCommandBuffer* commands = SDL_AcquireGPUCommandBuffer(gpu);
    SDL_GPUTexture* swapchain = nullptr;
    // No swapchain image while the app is in the background (the game runs).
    if (commands && SDL_WaitAndAcquireGPUSwapchainTexture(commands, window, &swapchain, nullptr, nullptr) &&
        swapchain) {
      ImGui_ImplSDLGPU3_PrepareDrawData(draw_data, commands);
      SDL_GPUColorTargetInfo target = {};
      target.texture = swapchain;
      target.clear_color = SDL_FColor{0, 0, 0, 1};
      target.load_op = SDL_GPU_LOADOP_CLEAR;
      target.store_op = SDL_GPU_STOREOP_STORE;
      SDL_GPURenderPass* pass = SDL_BeginGPURenderPass(commands, &target, 1, nullptr);
      ImGui_ImplSDLGPU3_RenderDrawData(draw_data, commands, pass);
      SDL_EndGPURenderPass(pass);
    } else {
      SDL_Delay(50);  // in the background: no need to spin
    }
    if (commands) SDL_SubmitGPUCommandBuffer(commands);
    // A menu needs no more than 60 images per second, even on a 120 Hz screen.
    auto spent = std::chrono::steady_clock::now() - now;
    if (spent < std::chrono::milliseconds(16)) {
      SDL_DelayNS(uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::milliseconds(16) - spent).count()));
    }
  }

  SDL_WaitForGPUIdle(gpu);
  menu_owner.reset();
  ImGui_ImplSDLGPU3_Shutdown();
  ImGui_ImplSDL3_Shutdown();
  ImGui::DestroyContext();
  SDL_ReleaseWindowFromGPUDevice(gpu, window);
  SDL_DestroyGPUDevice(gpu);
  SDL_DestroyWindow(window);
  SDL_Quit();
  return 0;
}
