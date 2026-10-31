// slm-ime-keys: shows the key bindings of SLM IME in a browser, the keys in effect and those of the Default, MS-IME
// and ATOK profiles. fcitx5-configtool starts it from the button in the configuration of SLM IME. The configuration
// is read as the engine reads it (conf/llm-ime.conf); the page is written to $XDG_RUNTIME_DIR, readable by the user
// only, and opened with xdg-open. With --stdout the page is printed instead.
#include "key-table.h"
#include "slm-ime-config.h"

#include <fcitx-config/iniparser.h>
#include <fcitx-utils/i18n.h>
#include <fcitx-utils/key.h>

#include <clocale>
#include <cstdio>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

using namespace llmime;

int main(int argc, char **argv) {
    std::setlocale(LC_ALL, "");
    fcitx::registerDomain(FCITX_GETTEXT_DOMAIN, FCITX_INSTALL_LOCALEDIR);
    fcitx::registerDomain("fcitx5", FCITX_INSTALL_LOCALEDIR);  // the names of the keys

    SlmImeConfig config;
    fcitx::readAsIni(config, "conf/llm-ime.conf");
    std::vector<std::vector<std::string>> inEffect;
    for (size_t i = 0; i < actionInfos().size(); i++) {
        std::vector<std::string> keys;
        for (const auto &key : configKeys(config, i)) {
            keys.push_back(key.toString());
        }
        inEffect.push_back(keys);
    }

    KeyTableText text;
    text.title = _("Key bindings of SLM IME");
    text.operation = _("Operation");
    text.inEffect = _("Keys in effect");
    text.current = _(" (in effect)");
    text.typing = _("While typing the reading");
    text.converting = _("While converting");
    text.both = _("In both");
    text.translate = [](const char *s) { return std::string(_(s)); };
    std::string html = keyTableHtml(text, inEffect, *config.profile, [](const std::string &key) {
        return fcitx::Key(key).toString(fcitx::KeyStringFormat::Localized);
    });

    if (argc > 1 && std::strcmp(argv[1], "--stdout") == 0) {
        std::fputs(html.c_str(), stdout);
        return 0;
    }
    const char *runtime = std::getenv("XDG_RUNTIME_DIR");
    if (runtime == nullptr || *runtime == '\0') {
        std::fprintf(stderr, "slm-ime-keys: XDG_RUNTIME_DIR is not set\n");
        return 1;
    }
    std::string path = std::string(runtime) + "/slm-ime-keys.html";
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0 || write(fd, html.data(), html.size()) != static_cast<ssize_t>(html.size())) {
        std::fprintf(stderr, "slm-ime-keys: cannot write %s\n", path.c_str());
        return 1;
    }
    close(fd);
    execlp("xdg-open", "xdg-open", path.c_str(), static_cast<char *>(nullptr));
    std::fprintf(stderr, "slm-ime-keys: cannot start xdg-open: %s\n", std::strerror(errno));
    return 1;
}
