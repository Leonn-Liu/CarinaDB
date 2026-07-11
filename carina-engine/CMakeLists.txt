add_library(carina_engine INTERFACE)
target_include_directories(carina_engine INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(carina_engine INTERFACE carina_common)
target_compile_features(carina_engine INTERFACE cxx_std_17)
find_package(Threads REQUIRED)
target_link_libraries(carina_engine INTERFACE Threads::Threads)

# Add shared library for JNA integration
add_library(carina_engine_shared SHARED src/carina_c_api.cpp)
target_link_libraries(carina_engine_shared PRIVATE carina_engine)
set_target_properties(carina_engine_shared PROPERTIES OUTPUT_NAME "carina_engine")
