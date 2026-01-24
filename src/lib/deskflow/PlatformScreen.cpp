/*
 * Deskflow -- mouse and keyboard sharing utility
 * Copyright (C) 2012-2016 Symless Ltd.
 * Copyright (C) 2004 Chris Schoeneman
 *
 * This package is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * found in the file LICENSE that should have accompanied this file.
 *
 * This package is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "deskflow/PlatformScreen.h"
#include "deskflow/App.h"
#include "deskflow/ArgsBase.h"
#include "base/Log.h"

#include <cstdlib>
#include <fstream>
#include <sys/stat.h>

#if WINAPI_MSWINDOWS
#include <Windows.h>
#include <ShlObj.h>
#else
#include <unistd.h>
#include <sys/wait.h>
#endif

PlatformScreen::PlatformScreen(IEventQueue *events, deskflow::ClientScrollDirection scrollDirection)
    : IPlatformScreen(events),
      m_draggingStarted(false),
      m_fakeDraggingStarted(false),
      m_clientScrollDirection(scrollDirection)
{
}

PlatformScreen::~PlatformScreen()
{
  // do nothing
}

void PlatformScreen::updateKeyMap()
{
  getKeyState()->updateKeyMap();
}

void PlatformScreen::updateKeyState()
{
  getKeyState()->updateKeyState();
  updateButtons();
}

void PlatformScreen::setHalfDuplexMask(KeyModifierMask mask)
{
  getKeyState()->setHalfDuplexMask(mask);
}

void PlatformScreen::fakeKeyDown(KeyID id, KeyModifierMask mask, KeyButton button, const String &lang)
{
  getKeyState()->fakeKeyDown(id, mask, button, lang);
}

bool PlatformScreen::fakeKeyRepeat(KeyID id, KeyModifierMask mask, SInt32 count, KeyButton button, const String &lang)
{
  return getKeyState()->fakeKeyRepeat(id, mask, count, button, lang);
}

bool PlatformScreen::fakeKeyUp(KeyButton button)
{
  return getKeyState()->fakeKeyUp(button);
}

void PlatformScreen::fakeAllKeysUp()
{
  getKeyState()->fakeAllKeysUp();
}

bool PlatformScreen::fakeCtrlAltDel()
{
  return getKeyState()->fakeCtrlAltDel();
}

bool PlatformScreen::isKeyDown(KeyButton button) const
{
  return getKeyState()->isKeyDown(button);
}

KeyModifierMask PlatformScreen::getActiveModifiers() const
{
  return getKeyState()->getActiveModifiers();
}

KeyModifierMask PlatformScreen::pollActiveModifiers() const
{
  return getKeyState()->pollActiveModifiers();
}

SInt32 PlatformScreen::pollActiveGroup() const
{
  return getKeyState()->pollActiveGroup();
}

void PlatformScreen::pollPressedKeys(KeyButtonSet &pressedKeys) const
{
  getKeyState()->pollPressedKeys(pressedKeys);
}

void PlatformScreen::clearStaleModifiers()
{
  getKeyState()->clearStaleModifiers();
}

bool PlatformScreen::isDraggingStarted()
{
  if (App::instance().argsBase().m_enableDragDrop) {
    return m_draggingStarted;
  }
  return false;
}

SInt32 PlatformScreen::mapClientScrollDirection(SInt32 x) const
{
  return (x * m_clientScrollDirection);
}

static String getScriptDir()
{
#if WINAPI_MSWINDOWS
  char appData[MAX_PATH];
  if (SUCCEEDED(SHGetFolderPathA(NULL, CSIDL_APPDATA, NULL, 0, appData))) {
    return String(appData) + "\\Synergy\\scripts\\";
  }
  return "";
#else
  const char *home = std::getenv("HOME");
  if (home) {
    return String(home) + "/.synergy/scripts/";
  }
  return "";
#endif
}

static void ensureDirectory(const String &path)
{
#if WINAPI_MSWINDOWS
  CreateDirectoryA(path.c_str(), NULL);
  String parent = path.substr(0, path.rfind('\\'));
  if (!parent.empty() && parent != path) {
    CreateDirectoryA(parent.c_str(), NULL);
  }
#else
  String parent = path.substr(0, path.rfind('/'));
  if (!parent.empty()) {
    mkdir(parent.c_str(), 0755);
  }
  mkdir(path.c_str(), 0755);
#endif
}

void PlatformScreen::runScript(const String &name, const String &content)
{
  if (content.empty()) {
    LOG((CLOG_DEBUG "script \"%s\" has no content for this platform, skipping", name.c_str()));
    return;
  }

  String dir = getScriptDir();
  if (dir.empty()) {
    LOG((CLOG_ERR "could not determine script directory"));
    return;
  }

  ensureDirectory(dir);

#if WINAPI_MSWINDOWS
  String scriptPath = dir + name + ".ps1";
#else
  String scriptPath = dir + name + ".sh";
#endif

  // Check if script file exists with same content (skip rewrite for speed)
  bool needsWrite = true;
  std::ifstream existingFile(scriptPath, std::ios::binary);
  if (existingFile) {
    std::string existingContent((std::istreambuf_iterator<char>(existingFile)), std::istreambuf_iterator<char>());
    existingFile.close();
    if (existingContent == content) {
      needsWrite = false;
    }
  }

  if (needsWrite) {
    std::ofstream file(scriptPath, std::ios::binary);
    if (!file) {
      LOG((CLOG_ERR "failed to write script to %s", scriptPath.c_str()));
      return;
    }
    file << content;
    file.close();

#if !WINAPI_MSWINDOWS
    chmod(scriptPath.c_str(), 0755);
#endif
    LOG((CLOG_DEBUG "wrote script \"%s\" to %s", name.c_str(), scriptPath.c_str()));
  }

  LOG((CLOG_DEBUG "running script \"%s\"", name.c_str()));

  // Execute the script
#if WINAPI_MSWINDOWS
  String command = "powershell.exe -ExecutionPolicy Bypass -File \"" + scriptPath + "\"";

  STARTUPINFOA si;
  PROCESS_INFORMATION pi;
  ZeroMemory(&si, sizeof(si));
  si.cb = sizeof(si);
  ZeroMemory(&pi, sizeof(pi));

  if (CreateProcessA(NULL, const_cast<char *>(command.c_str()), NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
    LOG((CLOG_DEBUG "started script \"%s\"", name.c_str()));
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
  } else {
    LOG((CLOG_ERR "failed to run script \"%s\": error %lu", name.c_str(), GetLastError()));
  }
#else
  pid_t pid = fork();
  if (pid == 0) {
    execl("/bin/sh", "sh", scriptPath.c_str(), nullptr);
    _exit(1);
  } else if (pid > 0) {
    LOG((CLOG_DEBUG "started script \"%s\" with pid %d", name.c_str(), pid));
  } else {
    LOG((CLOG_ERR "failed to fork for script \"%s\"", name.c_str()));
  }
#endif
}
