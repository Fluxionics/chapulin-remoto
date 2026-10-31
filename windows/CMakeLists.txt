cmake_minimum_required(VERSION 3.25)

project(chapulin_remoto VERSION 0.1.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(MSVC)
    add_compile_options(/W4 /permissive- /utf-8 /Zc:__cplusplus)
    add_compile_definitions(_CRT_SECURE_NO_WARNINGS)
else()
    add_compile_options(-Wall -Wextra)
endif()

add_compile_definitions(
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
)

set(HOST_SOURCES
    src/main.cpp
    src/app/config.cpp
    src/app/session.cpp
    src/capture/gdi_capturer.cpp
    src/codec/wic_jpeg.cpp
    src/input/injector.cpp
    src/net/server.cpp
    src/net/phone_client.cpp
    src/ui/host_ui.cpp
    src/ui/phone_viewer.cpp
    resources/chapulin.rc
)

if(MSVC)
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} /SUBSYSTEM:WINDOWS /MANIFEST:NO")
endif()
add_executable(chapulin-host WIN32 ${HOST_SOURCES})

target_include_directories(chapulin-host PRIVATE src resources)

target_link_libraries(chapulin-host PRIVATE
    ws2_32
    ole32
    windowscodecs
    gdi32
    user32
    advapi32
    comctl32
    gdiplus
    kernel32
)
