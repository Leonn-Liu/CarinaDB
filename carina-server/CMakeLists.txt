add_executable(carina_server src/main.cpp)
target_link_libraries(carina_server PRIVATE carina_engine)

add_executable(validate_dataset test/validate_dataset.cpp)
target_link_libraries(validate_dataset PRIVATE carina_common)
