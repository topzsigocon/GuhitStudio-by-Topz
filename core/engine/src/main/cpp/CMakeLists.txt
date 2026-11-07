cmake_minimum_required(VERSION 3.22.1)
project(guhit_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(guhit_engine SHARED engine.cpp)

find_library(log-lib log)
target_link_libraries(guhit_engine GLESv3 EGL ${log-lib})
# Required for 16 KB page size devices (Android 15+)
target_link_options(guhit_engine PRIVATE -Wl,-z,max-page-size=16384)
