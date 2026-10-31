cmake_minimum_required(VERSION 3.16)
project(fcitx5-llm-ime VERSION 2.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)  # the fcitx5 headers of Fedora 44 and Debian sid use std::span
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(PkgConfig REQUIRED)
find_package(Fcitx5Core REQUIRED)
find_package(Fcitx5Module REQUIRED COMPONENTS Notifications)
find_package(nlohmann_json 3.2 REQUIRED)
# Eigen 3.3 or later (Ubuntu 24.04 has 3.4, Fedora 44 has 5): its configuration file accepts no other major version
# than the one asked for, so the version is checked here
find_package(Eigen3 REQUIRED NO_MODULE)
if(Eigen3_VERSION VERSION_LESS 3.3)
    message(FATAL_ERROR "Eigen 3.3 or later is needed (found ${Eigen3_VERSION})")
endif()


# fcitx5 addon install directory
include(GNUInstallDirs)
# Use fcitx5 macro to get addon install dir
set(FCITX_INSTALL_PKGDATADIR "${CMAKE_INSTALL_PREFIX}/share/fcitx5")
set(FCITX_INSTALL_ADDONDIR "${FCITX_INSTALL_PKGDATADIR}/addon")
set(FCITX_INSTALL_LIBDIR "${CMAKE_INSTALL_PREFIX}/${CMAKE_INSTALL_LIBDIR}/fcitx5")

# The conversion model, computed on the CPU with Eigen
add_library(slm-ime-model STATIC
    src/model/weights.cpp
    src/model/transformer.cpp
)
target_include_directories(slm-ime-model PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_link_libraries(slm-ime-model PUBLIC Eigen3::Eigen nlohmann_json::nlohmann_json)
set_target_properties(slm-ime-model PROPERTIES POSITION_INDEPENDENT_CODE ON)

# The conversion: the model, the readings of the bunsetsu, the user's choices and the candidates of Anthy
# Anthy is anthy on Debian and Ubuntu and anthy-unicode on Fedora, with the same API
pkg_search_module(ANTHY REQUIRED IMPORTED_TARGET anthy anthy-unicode)
add_library(slm-ime-conversion STATIC
    src/conversion/tokenizer.cpp
    src/conversion/reading-aligner.cpp
    src/conversion/user-history.cpp
    src/conversion/history-substitution.cpp
    src/conversion/anthy-candidates.cpp
    src/conversion/converter.cpp
)
target_link_libraries(slm-ime-conversion PUBLIC slm-ime-model PkgConfig::ANTHY)
set_target_properties(slm-ime-conversion PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(llm-ime MODULE
    src/llm-ime-engine.cpp
    src/local-conversion.cpp
)

target_link_libraries(llm-ime
    Fcitx5::Core
    slm-ime-conversion
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

# The conversion without the model: readings, history, substitution, the ends put back, Anthy (the tests of the
# Java conversion server of version 1)
add_executable(conversion-test test/conversion-test.cpp)
target_link_libraries(conversion-test PRIVATE slm-ime-conversion)
add_test(NAME conversion-test COMMAND conversion-test)

# A damaged weights file is refused, never read past its end (a tiny model is written by the test)
add_executable(weights-test test/weights-test.cpp)
target_link_libraries(weights-test PRIVATE slm-ime-model)
add_test(NAME weights-test COMMAND weights-test WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR})

# The model against the answers of PyTorch (needs SLM_IME_WEIGHTS and SLM_IME_GOLDEN; skipped without them)
add_executable(model-check test/model-check.cpp)
target_link_libraries(model-check PRIVATE slm-ime-model)
add_test(NAME model-check COMMAND model-check)
set_tests_properties(model-check PROPERTIES SKIP_RETURN_CODE 77)

# Answers requests with the C++ conversion, to compare them with those of the server of version 1
add_executable(conversion-dump test/conversion-dump.cpp)
target_link_libraries(conversion-dump PRIVATE slm-ime-conversion)
