cmake_minimum_required(VERSION 3.22)
project(ozen_whisper LANGUAGES C CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

file(SHA256 ${CMAKE_CURRENT_LIST_DIR}/whisper-abort.patch whisper_patch_sha256)
string(SUBSTRING ${whisper_patch_sha256} 0 12 whisper_patch_id)

FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
    SOURCE_DIR ${FETCHCONTENT_BASE_DIR}/whisper-src-${whisper_patch_id}
    PATCH_COMMAND patch -p1 --forward -i ${CMAKE_CURRENT_LIST_DIR}/whisper-abort.patch
)
FetchContent_MakeAvailable(whisper)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/whisper-abort.patch)

add_library(ozen_whisper SHARED whisper_jni.cpp)
target_link_libraries(ozen_whisper PRIVATE whisper log)
