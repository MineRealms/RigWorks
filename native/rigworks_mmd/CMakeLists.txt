cmake_minimum_required(VERSION 3.21)
project(rigworks_mmd VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

set(saba_root "${CMAKE_CURRENT_SOURCE_DIR}/third_party/saba")
set(bullet_root "${CMAKE_CURRENT_SOURCE_DIR}/third_party/bullet")
file(GLOB_RECURSE bullet_sources CONFIGURE_DEPENDS
    "${bullet_root}/src/BulletCollision/*.cpp"
    "${bullet_root}/src/BulletDynamics/*.cpp"
    "${bullet_root}/src/LinearMath/*.cpp")
add_library(rigworks_mmd_bullet STATIC ${bullet_sources})
target_include_directories(rigworks_mmd_bullet PUBLIC "${bullet_root}/src")
set_target_properties(rigworks_mmd_bullet PROPERTIES
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)

file(GLOB saba_base_sources CONFIGURE_DEPENDS "${saba_root}/src/Saba/Base/*.cpp")
file(GLOB saba_mmd_sources CONFIGURE_DEPENDS "${saba_root}/src/Saba/Model/MMD/*.cpp")
add_library(rigworks_mmd_saba STATIC ${saba_base_sources} ${saba_mmd_sources})
target_include_directories(rigworks_mmd_saba PUBLIC
    "${saba_root}/src" "${saba_root}/external/glm/include"
    "${saba_root}/external/spdlog/include" "${saba_root}/external/json/include")
find_package(Threads REQUIRED)
target_link_libraries(rigworks_mmd_saba PUBLIC rigworks_mmd_bullet Threads::Threads)
if(WIN32)
    target_compile_definitions(rigworks_mmd_saba PRIVATE UNICODE _UNICODE NOMINMAX)
endif()
set_target_properties(rigworks_mmd_saba PROPERTIES
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)
if(MSVC)
    target_compile_options(rigworks_mmd_saba PRIVATE /utf-8)
endif()

add_library(rigworks_mmd SHARED src/converter.cpp src/preflight.cpp)
target_include_directories(rigworks_mmd PUBLIC include)
target_compile_definitions(rigworks_mmd PRIVATE RIGWORKS_MMD_BUILD)
target_link_libraries(rigworks_mmd PRIVATE rigworks_mmd_saba)
set_target_properties(rigworks_mmd PROPERTIES
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)
if(MSVC)
    target_compile_options(rigworks_mmd PRIVATE /W4 /permissive- /utf-8)
endif()

set(RWMD_RESOURCE_PREFIX "" CACHE STRING "JNA platform prefix for staged native libraries")
if(NOT RWMD_RESOURCE_PREFIX)
    string(TOLOWER "${CMAKE_SYSTEM_PROCESSOR};${CMAKE_GENERATOR_PLATFORM};${CMAKE_OSX_ARCHITECTURES}" architecture)
    if(architecture MATCHES "aarch64|arm64")
        set(rwmd_arch aarch64)
    elseif(architecture MATCHES "x86_64|amd64|x64")
        set(rwmd_arch x86-64)
    else()
        message(FATAL_ERROR "Unsupported architecture; set RWMD_RESOURCE_PREFIX explicitly")
    endif()
    if(WIN32)
        set(RWMD_RESOURCE_PREFIX "win32-${rwmd_arch}")
    elseif(APPLE)
        set(RWMD_RESOURCE_PREFIX "darwin-${rwmd_arch}")
    elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        set(RWMD_RESOURCE_PREFIX "linux-${rwmd_arch}")
    else()
        message(FATAL_ERROR "Unsupported system; set RWMD_RESOURCE_PREFIX explicitly")
    endif()
endif()
message(STATUS "RigWorks MMD resource prefix: ${RWMD_RESOURCE_PREFIX}")
install(TARGETS rigworks_mmd
    RUNTIME DESTINATION "${RWMD_RESOURCE_PREFIX}" COMPONENT RigWorksMmd
    LIBRARY DESTINATION "${RWMD_RESOURCE_PREFIX}" COMPONENT RigWorksMmd)
install(FILES include/rigworks_mmd.h DESTINATION include COMPONENT RigWorksMmd)
install(FILES "${saba_root}/LICENCE" DESTINATION licenses RENAME saba-LICENSE COMPONENT RigWorksMmd)
install(FILES "${saba_root}/licenses/glm-LICENSE" "${saba_root}/licenses/spdlog-LICENSE"
    "${saba_root}/licenses/json-LICENSE" DESTINATION licenses COMPONENT RigWorksMmd)
install(FILES "${bullet_root}/LICENSE.txt" DESTINATION licenses RENAME bullet-LICENSE COMPONENT RigWorksMmd)
install(DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/third_party/toolchain-licenses/"
    DESTINATION licenses COMPONENT RigWorksMmd)

include(CTest)
if(BUILD_TESTING)
    add_executable(mmd_build_abi_test tests/mmd_build_abi_test.cpp tests/mmd_build_c_header_test.c)
    target_link_libraries(mmd_build_abi_test PRIVATE rigworks_mmd Threads::Threads)
    add_test(NAME mmd.c_abi COMMAND mmd_build_abi_test)
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/mmd_converter_test.cpp")
        add_executable(mmd_converter_test tests/mmd_converter_test.cpp)
        target_link_libraries(mmd_converter_test PRIVATE rigworks_mmd rigworks_mmd_saba)
        add_test(NAME mmd.converter COMMAND mmd_converter_test)
    endif()
endif()
