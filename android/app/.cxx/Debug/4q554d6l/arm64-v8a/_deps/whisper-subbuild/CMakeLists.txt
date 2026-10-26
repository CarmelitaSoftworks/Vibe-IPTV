# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION 3.22.1-g37088a8)

# We name the project and the target for the ExternalProject_Add() call
# to something that will highlight to the user what we are working on if
# something goes wrong and an error message is produced.

project(whisper-populate NONE)



include(ExternalProject)
ExternalProject_Add(whisper-populate
                     "UPDATE_DISCONNECTED" "False" "URL" "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz" "URL_HASH" "SHA256=57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae"
                    SOURCE_DIR          "/home/user/Vibe-IPTV/android/app/.cxx/Debug/4q554d6l/arm64-v8a/_deps/whisper-src"
                    BINARY_DIR          "/home/user/Vibe-IPTV/android/app/.cxx/Debug/4q554d6l/arm64-v8a/_deps/whisper-build"
                    CONFIGURE_COMMAND   ""
                    BUILD_COMMAND       ""
                    INSTALL_COMMAND     ""
                    TEST_COMMAND        ""
                    USES_TERMINAL_DOWNLOAD  YES
                    USES_TERMINAL_UPDATE    YES
)


