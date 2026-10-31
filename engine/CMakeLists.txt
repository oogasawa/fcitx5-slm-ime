cmake_minimum_required(VERSION 3.16)
project(fcitx5-llm-ime VERSION 1.2.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(PkgConfig REQUIRED)
find_package(Fcitx5Core REQUIRED)
find_package(Fcitx5Module REQUIRED COMPONENTS Notifications)
find_package(CURL REQUIRED)
find_package(nlohmann_json 3.2 REQUIRED)


# fcitx5 addon install directory
include(GNUInstallDirs)
# Use fcitx5 macro to get addon install dir
set(FCITX_INSTALL_PKGDATADIR "${CMAKE_INSTALL_PREFIX}/share/fcitx5")
set(FCITX_INSTALL_ADDONDIR "${FCITX_INSTALL_PKGDATADIR}/addon")
set(FCITX_INSTALL_LIBDIR "${CMAKE_INSTALL_PREFIX}/${CMAKE_INSTALL_LIBDIR}/fcitx5")

add_library(llm-ime MODULE
    src/llm-ime-engine.cpp
    src/http-client.cpp
)

target_link_libraries(llm-ime
    Fcitx5::Core
    CURL::libcurl
    nlohmann_json::nlohmann_json

)

target_include_directories(llm-ime PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)
# The labels of the configuration are English; po/ has their translations
target_compile_definitions(llm-ime PRIVATE FCITX_GETTEXT_DOMAIN="fcitx5-slm-ime"
                                           FCITX_INSTALL_LOCALEDIR="${CMAKE_INSTALL_FULL_LOCALEDIR}")

# slm-ime-keys shows the keys of every profile in a browser; the configuration has a button that starts it
target_compile_definitions(llm-ime PRIVATE SLM_IME_KEYS_PATH="${CMAKE_INSTALL_FULL_BINDIR}/slm-ime-keys")
add_executable(slm-ime-keys src/slm-ime-keys.cpp src/key-table.cpp)
target_include_directories(slm-ime-keys PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(slm-ime-keys PRIVATE Fcitx5::Core)
target_compile_definitions(slm-ime-keys PRIVATE FCITX_GETTEXT_DOMAIN="fcitx5-slm-ime"
                                                FCITX_INSTALL_LOCALEDIR="${CMAKE_INSTALL_FULL_LOCALEDIR}"
                                                SLM_IME_KEYS_PATH="${CMAKE_INSTALL_FULL_BINDIR}/slm-ime-keys")

set_target_properties(llm-ime PROPERTIES
    PREFIX ""
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib"
)

# Translations
find_package(Gettext REQUIRED)
add_subdirectory(po)

# Install
install(TARGETS llm-ime DESTINATION "${FCITX_INSTALL_LIBDIR}")
install(TARGETS slm-ime-keys DESTINATION "${CMAKE_INSTALL_BINDIR}")
install(FILES src/llm-ime-addon.conf DESTINATION "${FCITX_INSTALL_ADDONDIR}" RENAME llm-ime.conf)
install(FILES src/llm-ime.conf DESTINATION "${FCITX_INSTALL_PKGDATADIR}/inputmethod" RENAME llm-ime.conf)

# Tests that need no fcitx5: the cursor of the reading
enable_testing()
add_executable(romaji-test test/romaji-test.cpp)
target_include_directories(romaji-test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
add_test(NAME romaji-test COMMAND romaji-test)

# Tests that need no fcitx5: the bunsetsu that are learned
add_executable(corrections-test test/corrections-test.cpp)
target_include_directories(corrections-test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
add_test(NAME corrections-test COMMAND corrections-test)

# Tests that need no fcitx5: the committed text that stands for the text before the cursor and that can be taken back
add_executable(committed-text-test test/committed-text-test.cpp)
target_include_directories(committed-text-test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
add_test(NAME committed-text-test COMMAND committed-text-test)

# Tests that need no fcitx5: the reading in other kinds of characters (F6 to F10)
add_executable(char-kinds-test test/char-kinds-test.cpp)
target_include_directories(char-kinds-test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
add_test(NAME char-kinds-test COMMAND char-kinds-test)

# Tests of the key binding profiles (the fcitx5 utilities read the keys)
add_executable(key-actions-test test/key-actions-test.cpp)
target_include_directories(key-actions-test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(key-actions-test PRIVATE Fcitx5::Utils)
add_test(NAME key-actions-test COMMAND key-actions-test)

# The table of the keys that slm-ime-keys shows: every operation once, in its group, with the keys of the profiles
add_executable(key-table-test test/key-table-test.cpp src/key-table.cpp)
target_include_directories(key-table-test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
add_test(NAME key-table-test COMMAND key-table-test)

# The configuration in three groups: every operation in one group, the keys of the profiles, save and load
add_executable(slm-ime-config-test test/slm-ime-config-test.cpp)
target_include_directories(slm-ime-config-test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(slm-ime-config-test PRIVATE Fcitx5::Core)
target_compile_definitions(slm-ime-config-test PRIVATE FCITX_GETTEXT_DOMAIN="fcitx5-slm-ime" SLM_IME_KEYS_PATH="slm-ime-keys")
add_test(NAME slm-ime-config-test COMMAND slm-ime-config-test)
