#pragma once

#include <string>
#include <nlohmann/json.hpp>

namespace llm_ime {

// Simple HTTP client for communicating with quarkus-llm-ime daemon.
class HttpClient {
public:
    explicit HttpClient(const std::string &baseUrl);
    ~HttpClient();

    // POST JSON to the given path, return parsed JSON response.
    nlohmann::json post(const std::string &path, const nlohmann::json &body);

    // GET from the given path (with query string), return parsed JSON response.
    nlohmann::json get(const std::string &pathWithQuery);

    const std::string &baseUrl() const { return baseUrl_; }

private:
    std::string baseUrl_;
};

} // namespace llm_ime
