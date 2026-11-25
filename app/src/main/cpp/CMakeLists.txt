# Native libraries of the app, built by ../../../../build.sh:
#   liblanceur.so  the launcher menu (lanceur.cpp): SDL3, Dear ImGui, stb_image, LibAtrac9, zlib
#   libecran.so    the game screen and engine start-up (ecran.c), loaded in the game's process
#
# Inputs (all set by build.sh):
#   THIRD_PARTY   the repository's third_party folder (imgui, stb, LibAtrac9 submodules)
#   SDL_INSTALL   an SDL3 static build for Android (build.sh builds one from third_party/SDL)

cmake_minimum_required(VERSION 3.25)
project(nba2k14_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(IMGUI "${THIRD_PARTY}/imgui")
set(ATRAC9 "${THIRD_PARTY}/LibAtrac9/C/src")
file(GLOB ATRAC9_SOURCES "${ATRAC9}/*.c")

add_library(lanceur SHARED
  lanceur.cpp
  ${IMGUI}/imgui.cpp
  ${IMGUI}/imgui_draw.cpp
  ${IMGUI}/imgui_tables.cpp
  ${IMGUI}/imgui_widgets.cpp
  ${IMGUI}/backends/imgui_impl_sdl3.cpp
  ${IMGUI}/backends/imgui_impl_sdlgpu3.cpp
  ${ATRAC9_SOURCES}
)
target_include_directories(lanceur PRIVATE ${IMGUI} ${IMGUI}/backends ${THIRD_PARTY}/stb ${ATRAC9}
                                           ${SDL_INSTALL}/include)
target_compile_definitions(lanceur PRIVATE IMGUI_DISABLE_OBSOLETE_FUNCTIONS)
target_link_libraries(lanceur PRIVATE ${SDL_INSTALL}/lib/libSDL3.a android log GLESv2 EGL OpenSLES aaudio z dl m)
# SDL's JNI_OnLoad registers its Java natives; nothing else references it.
# 16 KB pages (some recent phones, and a Play Store rule since Android 15): aligned for both page sizes.
target_link_options(lanceur PRIVATE "-Wl,-u,JNI_OnLoad" "-Wl,--no-undefined" "-Wl,-z,max-page-size=16384")

add_library(ecran SHARED ecran.c)
target_compile_options(ecran PRIVATE -O2)
target_link_libraries(ecran PRIVATE dl log)
target_link_options(ecran PRIVATE "-Wl,-z,max-page-size=16384")
