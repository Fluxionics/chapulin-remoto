#pragma once

#include <windows.h>
#include <memory>
#include <string>

#include "net/phone_client.h"

namespace chp {
namespace ui {

void openPhoneViewer(HINSTANCE hInst, std::shared_ptr<client::PhoneClient> client, const std::string& phoneIp);
void closePhoneViewer();

}
}
