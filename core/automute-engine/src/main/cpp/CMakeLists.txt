cmake_minimum_required(VERSION 3.22)
project(hushcast_native C CXX)

# Two JNI libraries: libhushcast_llama.so (embeddings) and libhushcast_whisper.so (STT + DTW + Silero VAD).
# llama.cpp and whisper.cpp each bundle their own copy of ggml, so they can't share one CMake
# build; each is built on its own as static libraries, and its symbols are kept private to the
# JNI library that uses it.

include(ExternalProject)
find_package(Threads REQUIRED)

set(LLAMA_TAG   "b11671" CACHE STRING "llama.cpp release tag")
set(WHISPER_TAG "v1.9.5" CACHE STRING "whisper.cpp release tag")
set(HUSHCAST_ARM_ARCH "armv8-a" CACHE STRING "CPU features for Android arm64 builds")

set(COMMON_ARGS
    -DCMAKE_BUILD_TYPE=Release
    -DBUILD_SHARED_LIBS=OFF
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DCMAKE_INSTALL_LIBDIR=lib
    -DGGML_OPENMP=OFF)
if(ANDROID)
    list(APPEND COMMON_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_STL=${ANDROID_STL}
        -DGGML_NATIVE=OFF
        -DGGML_CPU_ARM_ARCH=${HUSHCAST_ARM_ARCH})
else()
    list(APPEND COMMON_ARGS -DGGML_NATIVE=ON)
endif()

if(CMAKE_MAKE_PROGRAM)
    list(APPEND COMMON_ARGS -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM})
endif()

set(GGML_LIBS libggml.a libggml-cpu.a libggml-base.a)

set(EXTRACT_TS "")
if(CMAKE_VERSION VERSION_GREATER_EQUAL 3.24)
    set(EXTRACT_TS DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
endif()

set(LLAMA_PREFIX ${CMAKE_BINARY_DIR}/llama-install)
set(LLAMA_LIBS ${LLAMA_PREFIX}/lib/libllama.a)
foreach(l ${GGML_LIBS})
    list(APPEND LLAMA_LIBS ${LLAMA_PREFIX}/lib/${l})
endforeach()
ExternalProject_Add(llama_ext
    URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_TAG}.tar.gz
    URL_HASH SHA256=dbbbf2509a404ff5479bce0507360fb0e1ff655811974fe5e79e4312a2902c52
    ${EXTRACT_TS}
    CMAKE_ARGS ${COMMON_ARGS} -DCMAKE_INSTALL_PREFIX=${LLAMA_PREFIX}
        -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF
        -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_OPENSSL=OFF
    BUILD_BYPRODUCTS ${LLAMA_LIBS})

set(WHISPER_PREFIX ${CMAKE_BINARY_DIR}/whisper-install)
set(WHISPER_LIBS ${WHISPER_PREFIX}/lib/libwhisper.a)
foreach(l ${GGML_LIBS})
    list(APPEND WHISPER_LIBS ${WHISPER_PREFIX}/lib/${l})
endforeach()
ExternalProject_Add(whisper_ext
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/${WHISPER_TAG}.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
    ${EXTRACT_TS}
    CMAKE_ARGS ${COMMON_ARGS} -DCMAKE_INSTALL_PREFIX=${WHISPER_PREFIX}
        -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_SERVER=OFF
    BUILD_BYPRODUCTS ${WHISPER_LIBS})

if(NOT ANDROID)
    find_package(JNI REQUIRED)
endif()

function(jni_library name source ext prefix libs)
    add_library(${name} SHARED ${source})
    add_dependencies(${name} ${ext})
    target_compile_features(${name} PRIVATE cxx_std_17)
    target_compile_options(${name} PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden)
    target_include_directories(${name} PRIVATE ${prefix}/include ${JNI_INCLUDE_DIRS})
    target_link_libraries(${name} PRIVATE -Wl,--start-group ${libs} -Wl,--end-group
        Threads::Threads ${CMAKE_DL_LIBS} m)
    if(ANDROID)
        target_link_libraries(${name} PRIVATE log)
    endif()
    target_link_options(${name} PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-debug)
endfunction()

jni_library(hushcast_llama llama_jni.cpp llama_ext ${LLAMA_PREFIX} "${LLAMA_LIBS}")
jni_library(hushcast_whisper whisper_jni.cpp whisper_ext ${WHISPER_PREFIX} "${WHISPER_LIBS}")
