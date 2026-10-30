// Answers requests of the engine with the C++ conversion, to compare its answers with those of the conversion
// server of version 1 (eval/compare_cpp_java.py).
//
// Usage: conversion-dump <weights> <tokenizer.json> < requests > answers
// Every input line is {"path": "/api/...", "request": {...}}; every output line is the answer, JSON, and the time
// it took. The history is kept in memory only, and Anthy gives the dictionary candidates.
#include "conversion/converter.h"

#include <chrono>
#include <iostream>
#include <string>

using namespace llmime::conversion;

int main(int argc, char **argv) {
    if (argc < 3) {
        std::cerr << "usage: conversion-dump <weights> <tokenizer.json> < requests\n";
        return 2;
    }
    ConverterSettings settings;
    settings.weightsPath = argv[1];
    settings.tokenizerPath = argv[2];
    std::unique_ptr<CandidateSource> anthy;
    try {
        anthy = std::make_unique<AnthyCandidates>();
    } catch (const std::exception &e) {
        std::cerr << "no Anthy: " << e.what() << "\n";
    }
    Converter converter(settings, std::move(anthy));
    std::string line;
    while (std::getline(std::cin, line)) {
        auto in = nlohmann::json::parse(line);
        auto t0 = std::chrono::steady_clock::now();
        auto answer = converter.post(in.at("path").get<std::string>(), in.at("request"));
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::cout << nlohmann::json{{"answer", answer}, {"ms", ms}}.dump() << "\n";
    }
    return 0;
}
