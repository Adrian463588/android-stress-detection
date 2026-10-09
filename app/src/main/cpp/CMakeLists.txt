cmake_minimum_required(VERSION 3.22.1)
project(verbatim_prosody LANGUAGES C CXX)

# Source is checksum-verified by prepareOpenSmile. No second microphone,
# network client, model, or prebuilt binary is part of this runtime.
set(STATIC_LINK ON CACHE BOOL "" FORCE)
set(SMILEAPI_STATIC_LINK ON CACHE BOOL "" FORCE)
set(WITH_PORTAUDIO OFF CACHE BOOL "" FORCE)
set(WITH_OPENSLES OFF CACHE BOOL "" FORCE)
set(WITH_FFMPEG OFF CACHE BOOL "" FORCE)
set(WITH_OPENCV OFF CACHE BOOL "" FORCE)
add_subdirectory("${OPENSMILE_SOURCE}" opensmile EXCLUDE_FROM_ALL)
add_library(verbatim_prosody SHARED prosody_jni.cpp)
target_compile_features(verbatim_prosody PRIVATE cxx_std_17)
target_link_libraries(verbatim_prosody PRIVATE SMILEapi)
target_link_options(verbatim_prosody PRIVATE "-Wl,-z,max-page-size=16384")

add_library(secure_file_ops SHARED secure_file_ops_jni.cpp)
target_compile_features(secure_file_ops PRIVATE cxx_std_17)
target_link_options(secure_file_ops PRIVATE "-Wl,-z,max-page-size=16384")
