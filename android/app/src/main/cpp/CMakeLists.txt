# Subtítulos con Whisper (whisper.cpp, MIT). Sólo para arm64-v8a: teléfonos de gama media-alta.
# En las demás arquitecturas se compila un sustituto que responde "no disponible".
cmake_minimum_required(VERSION 3.22.1)
project(vibewhisper C CXX)
set(CMAKE_CXX_STANDARD 17)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    include(FetchContent)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    # Procesadores ARMv8.2 con fp16 y dotprod (gama media-alta desde ~2019). La app revisa que el
    # procesador los tenga antes de cargar esta librería.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
    FetchContent_Declare(whisper
        URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz
        URL_HASH SHA256=57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae)
    FetchContent_MakeAvailable(whisper)
    add_library(vibewhisper SHARED vibe_whisper.cpp)
    target_compile_options(vibewhisper PRIVATE -O3)
    target_link_libraries(vibewhisper PRIVATE whisper log)
    target_link_options(vibewhisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
else()
    add_library(vibewhisper SHARED vibe_whisper.cpp)
    target_compile_definitions(vibewhisper PRIVATE VIBE_NO_WHISPER=1)
    target_link_libraries(vibewhisper PRIVATE log)
endif()
