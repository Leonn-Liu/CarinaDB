cmake_minimum_required(VERSION 3.16)
project(CarinaDB CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_CXX_FLAGS_RELEASE "-O3")

add_subdirectory(carina-common)
add_subdirectory(carina-engine)
add_subdirectory(carina-server)
