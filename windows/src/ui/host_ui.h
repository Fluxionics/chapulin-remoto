#pragma once

#include <windows.h>
#include <functional>
#include <string>
#include <vector>

namespace chp {
namespace ui {

void showHostWindow(const std::string& pin, uint16_t port, const std::vector<std::string>& ips);

// status/client son UTF-8. active pinta el estado en verde (sesion en curso).
void updateHostStatus(const std::string& status, const std::string& client, bool active = false);

// Se invoca desde el hilo de UI cuando el usuario pulsa Salir o cierra la ventana.
void setExitHandler(std::function<void()> handler);

}  // namespace ui
}  // namespace chp
