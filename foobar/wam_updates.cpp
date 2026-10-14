#include <winsock2.h>
#include <windows.h>
#include <mmsystem.h>
#include <objidl.h>
#include <shellapi.h>
#include <winhttp.h>
#include <foobar2000/SDK/foobar2000.h>

#include "wam_updates.h"

#if __has_include("wam_build_sha.h")
#include "wam_build_sha.h"
#endif
#ifndef WAMBRIDGE_BUILD_SHA
#define WAMBRIDGE_BUILD_SHA ""
#endif

#include <algorithm>
#include <atomic>
#include <cctype>
#include <optional>
#include <mutex>
#include <utility>
#include <string>
#include <string_view>
#include <thread>

namespace {

constexpr wchar_t kApiHost[] = L"api.github.com";
constexpr wchar_t kReleaseApiPath[] =
    L"/repos/travnie/wambridge/releases/tags/latest";
constexpr wchar_t kReleasePage[] =
    L"https://github.com/travnie/wambridge/releases/tag/latest";
constexpr size_t kMaxResponseBytes = 256 * 1024;
std::atomic<bool> g_checking{false};
std::atomic<bool> g_stopping{false};
std::mutex g_worker_mutex;
std::thread g_worker;

struct InternetHandle {
    HINTERNET value = nullptr;

    explicit InternetHandle(HINTERNET handle) : value(handle) {}
    ~InternetHandle() {
        if (value != nullptr) WinHttpCloseHandle(value);
    }
    InternetHandle(const InternetHandle&) = delete;
    InternetHandle& operator=(const InternetHandle&) = delete;
    explicit operator bool() const { return value != nullptr; }
};

struct ReleaseCheck {
    std::string sha;
    std::wstring error;
};

std::optional<std::string> json_string(
    const std::string& json,
    std::string_view key
) {
    const std::string marker = "\"" + std::string(key) + "\"";
    const auto pos = json.find(marker);
    if (pos == std::string::npos) return std::nullopt;

    auto start = json.find(':', pos + marker.size());
    if (start == std::string::npos) return std::nullopt;
    ++start;
    while (start < json.size() &&
           (json[start] == ' ' || json[start] == '\n' || json[start] == '\r')) {
        ++start;
    }
    if (start >= json.size() || json[start++] != '"') return std::nullopt;
    const auto end = json.find('"', start);
    if (end == std::string::npos) return std::nullopt;
    const auto value = json.substr(start, end - start);
    if (value.find('\\') != std::string::npos) return std::nullopt;
    return value;
}

bool valid_sha(std::string_view value) {
    return value.size() == 40 &&
           std::all_of(value.begin(), value.end(), [](char c) {
               return (c >= '0' && c <= '9') ||
                      (c >= 'a' && c <= 'f') ||
                      (c >= 'A' && c <= 'F');
           });
}

ReleaseCheck fetch_latest_release() {
    InternetHandle session(WinHttpOpen(
        L"WAM Bridge update check", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
        WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0
    ));
    if (!session) return {{}, L"Cannot initialize WinHTTP."};
    if (!WinHttpSetTimeouts(session.value, 3000, 3000, 5000, 5000)) {
        return {{}, L"Cannot set GitHub request timeouts."};
    }

    InternetHandle connection(WinHttpConnect(
        session.value, kApiHost, INTERNET_DEFAULT_HTTPS_PORT, 0
    ));
    if (!connection) return {{}, L"Cannot connect to GitHub."};

    InternetHandle request(WinHttpOpenRequest(
        connection.value, L"GET", kReleaseApiPath,
        nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
        WINHTTP_FLAG_SECURE
    ));
    if (!request) return {{}, L"Cannot create the HTTPS request."};

    DWORD disableRedirects = WINHTTP_DISABLE_REDIRECTS;
    if (!WinHttpSetOption(
            request.value, WINHTTP_OPTION_DISABLE_FEATURE,
            &disableRedirects, sizeof(disableRedirects)
        )) {
        return {{}, L"Cannot disable HTTPS redirects."};
    }
    constexpr wchar_t headers[] = L"Accept: application/vnd.github+json";
    if (!WinHttpAddRequestHeaders(
            request.value, headers, static_cast<DWORD>(-1),
            WINHTTP_ADDREQ_FLAG_ADD
        ) ||
        !WinHttpSendRequest(
            request.value, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
            WINHTTP_NO_REQUEST_DATA, 0, 0, 0
        ) ||
        !WinHttpReceiveResponse(request.value, nullptr)) {
        return {{}, L"GitHub request failed (network, proxy or TLS)."};
    }

    DWORD code = 0;
    DWORD codeSize = sizeof(code);
    if (!WinHttpQueryHeaders(
            request.value, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
            WINHTTP_HEADER_NAME_BY_INDEX, &code, &codeSize,
            WINHTTP_NO_HEADER_INDEX
        )) {
        return {{}, L"Cannot read the GitHub HTTP status."};
    }
    if (code != 200) {
        return {{}, L"GitHub returned HTTP " + std::to_wstring(code) + L"."};
    }

    std::string body;
    char buffer[4096];
    for (;;) {
        DWORD bytesRead = 0;
        if (!WinHttpReadData(request.value, buffer, sizeof(buffer), &bytesRead)) {
            return {{}, L"Cannot read the GitHub response."};
        }
        if (bytesRead == 0) break;
        if (body.size() + bytesRead > kMaxResponseBytes) {
            return {{}, L"GitHub response exceeded the size limit."};
        }
        body.append(buffer, bytesRead);
    }

    // Rolling releases publish this exact commit; the display version stays 0.0.5.
    const auto sha = json_string(body, "target_commitish");
    if (!sha || !valid_sha(*sha)) {
        return {{}, L"Latest release has no valid commit identifier."};
    }
    // The publisher uploads assets before advertising a new target commit.
    // Reject an absent or unfinished component rather than suggesting a stale download.
    const auto assets = body.find("\"assets\"");
    const auto name = assets == std::string::npos
        ? std::string::npos
        : body.find("\"foo_out_wam.fb2k-component\"", assets);
    const auto objectEnd = name == std::string::npos
        ? std::string::npos : body.find('}', name);
    if (objectEnd == std::string::npos) {
        return {{}, L"The latest release has no foobar component asset."};
    }
    const auto asset = body.substr(name, objectEnd - name);
    if (json_string(asset, "state") != std::optional<std::string>("uploaded")) {
        return {{}, L"The foobar component asset is not ready."};
    }
    return {*sha, {}};
}

std::wstring short_sha(const std::string& value) {
    return std::wstring(value.begin(), value.begin() + 7);
}

void open_release_page() {
    const auto result = ShellExecuteW(
        nullptr, L"open", kReleasePage, nullptr, nullptr, SW_SHOWNORMAL
    );
    if (reinterpret_cast<INT_PTR>(result) <= 32) {
        MessageBoxW(nullptr, L"Could not open the GitHub release page in your browser.",
                    L"WAM Bridge updates", MB_OK | MB_ICONWARNING);
    }
}

// Dialogs run on foobar's main thread, never on the network worker.
class ShowUpdateResult final : public main_thread_callback {
public:
    explicit ShowUpdateResult(ReleaseCheck result) : m_result(std::move(result)) {}

    void callback_run() override {
        if (g_stopping.load()) return;
        try {
            if (!m_result.error.empty()) {
                MessageBoxW(nullptr, m_result.error.c_str(), L"WAM Bridge updates",
                            MB_OK | MB_ICONWARNING | MB_SETFOREGROUND);
                return;
            }
            const std::string installed = WAMBRIDGE_BUILD_SHA;
            if (valid_sha(installed) && installed == m_result.sha) {
                const std::wstring message =
                    L"WAM Bridge is up to date.\n\nInstalled build: " +
                    short_sha(installed);
                MessageBoxW(nullptr, message.c_str(), L"WAM Bridge updates",
                            MB_OK | MB_ICONINFORMATION | MB_SETFOREGROUND);
                return;
            }

            const std::wstring current = valid_sha(installed)
                ? short_sha(installed)
                : L"unknown (older or local build)";
            const std::wstring message =
                L"Installed build: " + current +
                L"\nLatest release: " + short_sha(m_result.sha) +
                L"\n\nOpen the GitHub release page to install the component?\n"
                L"This check does not modify or install files automatically.";
            if (MessageBoxW(
                    nullptr, message.c_str(), L"WAM Bridge updates",
                    MB_YESNO | MB_ICONQUESTION | MB_SETFOREGROUND
                ) == IDYES) {
                open_release_page();
            }
        } catch (...) {
            MessageBoxW(nullptr, L"Unable to display the WAM Bridge update result.",
                        L"WAM Bridge updates", MB_OK | MB_ICONWARNING);
        }
    }

private:
    ReleaseCheck m_result;
};

void check_worker() {
    ReleaseCheck latest;
    try {
        latest = fetch_latest_release();
    } catch (...) {
        latest.error = L"Unable to check for WAM Bridge updates.";
    }
    if (!g_stopping.load()) {
        try {
            main_thread_callback_manager::get()->add_callback(
                new service_impl_t<ShowUpdateResult>(std::move(latest))
            );
        } catch (...) {
            // Shutdown may have started; no UI or SDK callbacks from this worker.
        }
    }
    g_checking.store(false);
}

class UpdateShutdown final : public initquit {
public:
    void on_init() override {}
    void on_quit() override {
        g_stopping.store(true);
        std::thread owned;
        {
            std::lock_guard<std::mutex> lock(g_worker_mutex);
            owned = std::move(g_worker);
        }
        if (owned.joinable()) owned.join();
    }
};

initquit_factory_t<UpdateShutdown> g_update_shutdown;

}  // namespace

namespace wam {

void check_for_updates() {
    if (g_stopping.load() || g_checking.exchange(true)) return;
    std::lock_guard<std::mutex> lock(g_worker_mutex);
    if (g_stopping.load()) {
        g_checking.store(false);
        return;
    }
    // A previous check has finished before g_checking became false.
    if (g_worker.joinable()) g_worker.join();
    try {
        g_worker = std::thread(check_worker);
    } catch (...) {
        g_checking.store(false);
        MessageBoxW(nullptr, L"Unable to start update check.",
                    L"WAM Bridge updates", MB_OK | MB_ICONWARNING);
    }
}

}  // namespace wam
