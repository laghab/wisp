cmake_minimum_required(VERSION 3.22)
project(wisp_native CXX)

# HeliBoard / AOSP LatinIME dictionary engine (Apache-2.0 and GPL-3.0, see THIRD_PARTY.md), built as libjni_latinime.
set(LATINIME ${CMAKE_CURRENT_SOURCE_DIR}/latinime)

file(GLOB_RECURSE LATINIME_SOURCES CONFIGURE_DEPENDS ${LATINIME}/*.cpp)
add_library(jni_latinime SHARED ${LATINIME_SOURCES})

target_include_directories(jni_latinime PRIVATE ${LATINIME} ${LATINIME}/src)
set_target_properties(jni_latinime PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_options(jni_latinime PRIVATE -O2 -ffunction-sections -fdata-sections -w)
target_link_options(jni_latinime PRIVATE -Wl,--gc-sections -Wl,--build-id=none -Wl,-z,max-page-size=16384)
target_link_libraries(jni_latinime log dl)
