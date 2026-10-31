#pragma once

#include <windows.h>
#include <functional>
#include <string>
#include <vector>

namespace chp {
namespace ui {

void showHostWindow(const std::string& pin, uint16_t port, const std::vector<std::string>& ips);
void updateHostStatus(const std::string& status, const std::string& client, bool active = false);
void setExitHandler(std::function<void()> handler);

}
}
