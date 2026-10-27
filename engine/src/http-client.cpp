#include "http-client.h"
#include <curl/curl.h>
#include <stdexcept>

namespace llm_ime {

static size_t writeCallback(void *contents, size_t size, size_t nmemb,
                            std::string *userp) {
    userp->append(static_cast<char *>(contents), size * nmemb);
    return size * nmemb;
}

namespace {
struct CurlGlobalInit {
    CurlGlobalInit() { curl_global_init(CURL_GLOBAL_DEFAULT); }
    ~CurlGlobalInit() { curl_global_cleanup(); }
};
static CurlGlobalInit curlInit;
} // namespace

HttpClient::HttpClient(const std::string &baseUrl) : baseUrl_(baseUrl) {}

HttpClient::~HttpClient() {}

nlohmann::json HttpClient::post(const std::string &path,
                                 const nlohmann::json &body) {
    CURL *curl = curl_easy_init();
    if (!curl) {
        throw std::runtime_error("Failed to init curl");
    }

    std::string url = baseUrl_ + path;
    std::string requestBody = body.dump();
    std::string responseBody;

    struct curl_slist *headers = nullptr;
    headers = curl_slist_append(headers, "Content-Type: application/json");

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, requestBody.c_str());
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseBody);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 5L);

    CURLcode res = curl_easy_perform(curl);

    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    if (res != CURLE_OK) {
        throw std::runtime_error(std::string("HTTP request failed: ") +
                                  curl_easy_strerror(res));
    }

    return nlohmann::json::parse(responseBody);
}

nlohmann::json HttpClient::get(const std::string &pathWithQuery) {
    CURL *curl = curl_easy_init();
    if (!curl) {
        throw std::runtime_error("Failed to init curl");
    }

    std::string url = baseUrl_ + pathWithQuery;
    std::string responseBody;

    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_HTTPGET, 1L);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &responseBody);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 2L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 1L);

    CURLcode res = curl_easy_perform(curl);
    curl_easy_cleanup(curl);

    if (res != CURLE_OK) {
        throw std::runtime_error(std::string("HTTP GET failed: ") +
                                  curl_easy_strerror(res));
    }

    return nlohmann::json::parse(responseBody);
}

} // namespace llm_ime
