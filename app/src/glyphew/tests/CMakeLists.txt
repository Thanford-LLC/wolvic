cmake_minimum_required(VERSION 3.14)
project(glyphew_tests)
set(CMAKE_CXX_STANDARD 17)

# Fetch GoogleTest — requires internet access on first build.
# After first build the source is cached in the build directory.
include(FetchContent)
FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.14.0.tar.gz
)
# Prevent GoogleTest from overriding our compiler/linker settings on Windows.
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

add_executable(glyphew_tests
    ComboWindowEngineTest.cpp
    ../cpp/ComboWindowEngine.cpp
)

target_include_directories(glyphew_tests PRIVATE ../cpp)
target_link_libraries(glyphew_tests PRIVATE GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(glyphew_tests)
