#include "key-table.h"

namespace llmime {

namespace {

std::string escaped(const std::string &s) {
    std::string out;
    for (char c : s) {
        switch (c) {
        case '&': out += "&amp;"; break;
        case '<': out += "&lt;"; break;
        case '>': out += "&gt;"; break;
        case '"': out += "&quot;"; break;
        default: out += c;
        }
    }
    return out;
}

std::string keysCell(const std::vector<std::string> &keys,
                     const std::function<std::string(const std::string &)> &keyName, bool current) {
    std::string cell = current ? "<td class=\"current\">" : "<td>";
    for (size_t i = 0; i < keys.size(); i++) {
        cell += (i ? " " : "") + std::string("<kbd>") + escaped(keyName(keys[i])) + "</kbd>";
    }
    return cell + "</td>";
}

} // namespace

std::string keyTableHtml(const KeyTableText &text, const std::vector<std::vector<std::string>> &inEffect,
                         KeyProfile profile, const std::function<std::string(const std::string &)> &keyName) {
    const KeyProfile profiles[] = {KeyProfile::Default, KeyProfile::MSIME, KeyProfile::ATOK};
    const char *profileNames[] = {"Default", "MS-IME", "ATOK"};
    std::string html = "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>" + escaped(text.title) +
                       "</title>\n<style>\n"
                       "body { font-family: sans-serif; margin: 2em; color: #222; }\n"
                       "table { border-collapse: collapse; }\n"
                       "th, td { border: 1px solid #ccc; padding: 0.35em 0.7em; text-align: left; vertical-align: top; }\n"
                       "th { background: #f2f2f2; }\n"
                       "th.group { background: #e4ecf7; }\n"
                       "td.current, th.current { background: #fff6d6; }\n"
                       "kbd { border: 1px solid #bbb; border-radius: 3px; padding: 0 0.3em; background: #fafafa; "
                       "white-space: nowrap; }\n"
                       "</style></head><body>\n<h1>" + escaped(text.title) + "</h1>\n<table>\n<tr><th>" +
                       escaped(text.operation) + "</th><th>" + escaped(text.inEffect) + "</th>";
    for (size_t p = 0; p < 3; p++) {
        bool current = profiles[p] == profile;
        html += current ? "<th class=\"current\">" : "<th>";
        html += escaped(text.translate(profileNames[p])) + (current ? escaped(text.current) : "") + "</th>";
    }
    html += "</tr>\n";
    const std::string groups[] = {text.typing, text.converting, text.both};
    int group = -1;
    const auto &infos = actionInfos();
    for (size_t i = 0; i < infos.size(); i++) {
        if (actionGroup(infos[i].action) != group) {
            group = actionGroup(infos[i].action);
            html += "<tr><th class=\"group\" colspan=\"5\">" + escaped(groups[group]) + "</th></tr>\n";
        }
        html += "<tr><td>" + escaped(text.translate(infos[i].label)) + "</td>";
        html += keysCell(i < inEffect.size() ? inEffect[i] : std::vector<std::string>{}, keyName, false);
        for (KeyProfile p : profiles) {
            html += keysCell(profileKeys(infos[i], p), keyName, p == profile);
        }
        html += "</tr>\n";
    }
    return html + "</table>\n</body></html>\n";
}

} // namespace llmime
