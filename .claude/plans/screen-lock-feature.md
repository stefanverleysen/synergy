# Synergy Cross-Platform Screen Lock Feature

## Overview

When any connected machine locks its screen, automatically lock ALL other connected machines. Configurable via GUI checkbox in Server Configuration.

## Requirements

- **Behavior**: Lock all → when any machine locks, all others lock
- **GUI**: Checkbox in Server Config dialog to enable auto-lock propagation
- **Hotkey**: Global hotkey action "Lock All Screens" in Actions dropdown (works from any machine)
- **Platforms**: Windows, macOS, Linux/X11, Linux/Wayland (all at once)

---

## Implementation Summary

### New Components

| Component | Purpose |
|-----------|---------|
| `kOptionLockAllScreens` | Option ID for the auto-lock feature toggle |
| `kMsgDScreenLock` | Protocol message for lock propagation |
| `screenLocked()` / `screenUnlocked()` | New events in `IScreenEvents` |
| `lockScreen()` | New method in `IPlatformScreen` |
| `lockAllScreens` action type | New hotkey action in Action enum |
| `LockAllScreensAction` | New action class in InputFilter |

### Lock Flow

```
[Machine A locks]
    → Platform detects (WTS/CFNotification/D-Bus)
    → Emits screenLocked event
    → Server broadcasts kMsgDScreenLock to all clients
    → Each client calls lockScreen() on their platform
    → Loop prevention: message includes origin name
```

---

## Files to Modify

### Core Infrastructure

| File | Changes |
|------|---------|
| `src/lib/deskflow/option_types.h` | Add `kOptionLockAllScreens = OPTION_CODE("LKAS")` |
| `src/lib/deskflow/protocol_types.h` | Add `extern const char *const kMsgDScreenLock;` |
| `src/lib/deskflow/protocol_types.cpp` | Add `const char *const kMsgDScreenLock = "DSLK%1i%s";` |
| `src/lib/base/EventTypes.h` | Add `screenLocked()` and `screenUnlocked()` to `IScreenEvents` (after line 709) |
| `src/lib/base/EventTypes.cpp` | Register the new events with `REGISTER_EVENT` macro |

### Platform: Windows

| File | Changes |
|------|---------|
| `src/lib/platform/MSWindowsScreen.h` | Add `m_screenLocked`, `m_remoteLockPending`, `lockScreen()`, `onSessionLockStateChange()` (after line 314) |
| `src/lib/platform/MSWindowsScreen.cpp` | Implement WTS session notifications + `LockWorkStation()` |
| `src/lib/platform/CMakeLists.txt` | Link `Wtsapi32.lib` |

**Detailed Changes:**
- Add `#include <Wtsapi32.h>` (after line 45)
- Constructor (after line 151): `WTSRegisterSessionNotification(m_window, NOTIFY_FOR_THIS_SESSION)`
- Destructor (before line 203): `WTSUnRegisterSessionNotification(m_window)`
- `onEvent()` (after line 1076): Add `case WM_WTSSESSION_CHANGE:` handler
- New method `onSessionLockStateChange(bool locked)` following screensaver pattern (lines 1381-1409)
- `setOptions()` (line 476): Handle `kOptionLockAllScreens`

**Detection**: `WM_WTSSESSION_CHANGE` with `wParam == WTS_SESSION_LOCK` or `WTS_SESSION_UNLOCK`
**Trigger**: `LockWorkStation()` API

### Platform: macOS

| File | Changes |
|------|---------|
| `src/lib/platform/OSXScreen.h` | Add `m_screenLocked`, `m_remoteLockPending`, `lockScreen()`, static callback (after line 187) |
| `src/lib/platform/OSXScreen.mm` | Implement Darwin notification listener + CGSession lock |

**Detailed Changes:**
- Member variables (after line 347): `bool m_screenLocked`, `bool m_remoteLockPending`
- Static callback: `screenLockCallback(CFNotificationCenterRef, void*, CFStringRef, const void*, CFDictionaryRef)`
- Instance handler: `handleScreenLockChange(bool locked)`
- In `watchSystemPowerThread` (after line 1534): Register for Darwin notifications
- Cleanup (after line 1581): `CFNotificationCenterRemoveObserver()`
- `setOptions()` (line 917): Implement option handling (currently empty!)

**Detection**: `CFNotificationCenterGetDarwinNotifyCenter()` with:
  - `com.apple.screenIsLocked` (lock)
  - `com.apple.screenIsUnlocked` (unlock)
**Trigger**: `system("/System/Library/CoreServices/Menu\\ Extras/User.menu/Contents/Resources/CGSession -suspend")`

### Platform: Linux/X11

| File | Changes |
|------|---------|
| `src/lib/platform/XWindowsScreen.h` | Add `m_screenLocked`, `m_remoteLockPending`, `lockScreen()`, D-Bus handling (after line 260) |
| `src/lib/platform/XWindowsScreen.cpp` | Implement D-Bus signal listener + lock call |

**Detailed Changes:**
- Member variables (after line 260): D-Bus connection, `m_screenLocked` flag
- Constructor (after line 157): Setup D-Bus listener following `XWindowsScreenSaver` pattern
- `setOptions()` (lines 452-463): Handle `kOptionLockAllScreens`
- Event emission: Use existing `sendEvent()` pattern (lines 1125-1141)

**Detection**: D-Bus signals from:
  - `org.freedesktop.ScreenSaver.ActiveChanged`
  - `org.gnome.SessionManager` (fallback)
**Trigger**: D-Bus call to `org.freedesktop.login1.Session.Lock()` or `system("loginctl lock-session")`

**Note**: D-Bus infrastructure already exists in `ArchSystemUnix.cpp` with Qt6::DBus linked

### Platform: Linux/Wayland (EiScreen)

| File | Changes |
|------|---------|
| `src/lib/platform/EiScreen.h` | Add `m_screenLocked`, `lockScreen()`, D-Bus handling |
| `src/lib/platform/EiScreen.cpp` | Implement D-Bus signal listener (can reuse XWindowsPowerManager pattern) |

**Note**: EiScreen already uses `XWindowsPowerManager` (line 207 of EiScreen.h) - same D-Bus approach works

### Server Side

| File | Changes |
|------|---------|
| `src/lib/server/Server.h` | Add `m_lockAllScreens`, `m_lockOrigin`, lock event handlers |
| `src/lib/server/Server.cpp` | Handle lock events, broadcast to clients, process option in `processOptions()` |
| `src/lib/server/BaseClientProxy.h` | Add `virtual void screenLock(bool lock, const String& origin) = 0` |
| `src/lib/server/ClientProxy1_0.h` | Declare `screenLock()` override |
| `src/lib/server/ClientProxy1_0.cpp` | Implement `screenLock()` to send `kMsgDScreenLock` |
| `src/lib/server/Config.cpp` | Parse `lockAllScreens` option in `readSectionOptions()` (after line 699) |

### Client Side

| File | Changes |
|------|---------|
| `src/lib/client/ServerProxy.h` | Declare `screenLock()` parser method |
| `src/lib/client/ServerProxy.cpp` | Parse `kMsgDScreenLock` in `parseMessage()` (after line 320), call client's `screenLock()` |
| `src/lib/client/Client.h` | Add `void screenLock(bool lock, const String& origin)` |
| `src/lib/client/Client.cpp` | Implement to call `m_screen->lockScreen()` with origin check |

### Interface

| File | Changes |
|------|---------|
| `src/lib/deskflow/IPlatformScreen.h` | Add `virtual void lockScreen() = 0` |
| `src/lib/deskflow/PlatformScreen.h` | Add default empty `lockScreen()` implementation |

### GUI - Server Config

| File | Changes |
|------|---------|
| `src/gui/src/ServerConfig.h` | Add `m_LockAllScreens` (after line 261), getter `lockAllScreens()`, setter `setLockAllScreens()` |
| `src/gui/src/ServerConfig.cpp` | Add to `commit()` (line 128), `recall()` (line 184), `operator==` (line 80), `operator<<` (line 286) |
| `src/gui/src/ServerConfigDialog.cpp` | Wire checkbox: init (after line 106), connect signal (after line 147) |
| `src/gui/src/ServerConfigDialogBase.ui` | Add checkbox in Advanced tab after clipboard options |

### GUI - Hotkey Action

| File | Changes |
|------|---------|
| `src/gui/src/Action.h` | Add `lockAllScreens` to `ActionType` enum (after line 47) |
| `src/gui/src/Action.cpp` | Add `"lockAllScreens"` to `m_ActionTypeNames[]` array (line 24-27) |
| `src/gui/src/ActionDialog.cpp` | Add radio button handling for lockAllScreens (after line 53) |
| `src/gui/src/ActionDialogBase.ui` | Add radio button "Lock all screens" |

### Hotkey Action System (Server-Side)

| File | Changes |
|------|---------|
| `src/lib/server/InputFilter.h` | Add `LockAllScreensAction` class (after line 186, follow `RestartServer` pattern) |
| `src/lib/server/InputFilter.cpp` | Implement `LockAllScreensAction::perform()` - queues lock event to Server |
| `src/lib/server/Config.cpp` | Add parsing for `"lockAllScreens"` action in `parseAction()` (after line 1106) |
| `src/lib/server/Server.h` | Add `LockAllScreensInfo` struct and `lockAllScreens()` event type |
| `src/lib/server/Server.cpp` | Handle `lockAllScreens` event - broadcast lock to all clients |

---

## Implementation Order

### Phase 1: Core Infrastructure
1. `src/lib/deskflow/option_types.h` - Add `kOptionLockAllScreens = OPTION_CODE("LKAS")` after line 72
2. `src/lib/deskflow/protocol_types.h` - Add `extern const char *const kMsgDScreenLock;` after line 295
3. `src/lib/deskflow/protocol_types.cpp` - Add `const char *const kMsgDScreenLock = "DSLK%1i%s";`
4. `src/lib/base/EventTypes.h` - Add `screenLocked()`, `screenUnlocked()` to `IScreenEvents` (after line 709)
5. `src/lib/base/EventTypes.cpp` - Add `REGISTER_EVENT(IScreen, screenLocked)` and `REGISTER_EVENT(IScreen, screenUnlocked)`
6. `src/lib/deskflow/IPlatformScreen.h` - Add `virtual void lockScreen() = 0`

### Phase 2: Windows Platform
1. `MSWindowsScreen.cpp` - Add `#include <Wtsapi32.h>` after line 45
2. `MSWindowsScreen.h` - Add member vars `m_screenLocked`, `m_remoteLockPending` after line 314
3. `MSWindowsScreen.h` - Declare `onSessionLockStateChange(bool)`, `lockScreen()` after line 192
4. `MSWindowsScreen.cpp` constructor - Add `WTSRegisterSessionNotification(m_window, NOTIFY_FOR_THIS_SESSION)` after line 151
5. `MSWindowsScreen.cpp` destructor - Add `WTSUnRegisterSessionNotification(m_window)` before line 203
6. `MSWindowsScreen.cpp` `onEvent()` - Add `case WM_WTSSESSION_CHANGE:` after line 1076
7. `MSWindowsScreen.cpp` - Add `onSessionLockStateChange()` method after line 1409
8. `MSWindowsScreen.cpp` - Add `lockScreen()` method using `LockWorkStation()`
9. `MSWindowsScreen.cpp` `setOptions()` - Handle `kOptionLockAllScreens` at line 476
10. `src/lib/platform/CMakeLists.txt` - Link `Wtsapi32.lib`

### Phase 3: macOS Platform
1. `OSXScreen.h` - Add member vars after line 347
2. `OSXScreen.h` - Declare static `screenLockCallback()` and `handleScreenLockChange()` after line 187
3. `OSXScreen.mm` `watchSystemPowerThread` - Register Darwin notifications after line 1534
4. `OSXScreen.mm` - Implement `screenLockCallback()` static wrapper
5. `OSXScreen.mm` - Implement `handleScreenLockChange()` instance handler
6. `OSXScreen.mm` - Implement `lockScreen()` using CGSession
7. `OSXScreen.mm` `setOptions()` - Implement option handling (currently empty at line 917)
8. `OSXScreen.mm` cleanup - Unregister notifications after line 1581

### Phase 4: Linux Platform (X11 + Wayland)
1. `XWindowsScreen.h` - Add member vars after line 260
2. `XWindowsScreen.cpp` constructor - Setup D-Bus listener after line 157
3. `XWindowsScreen.cpp` - Implement D-Bus signal handler
4. `XWindowsScreen.cpp` - Implement `lockScreen()` via D-Bus or loginctl
5. `XWindowsScreen.cpp` `setOptions()` - Handle option at lines 452-463
6. `EiScreen.h` - Add member vars for Wayland
7. `EiScreen.cpp` - Implement D-Bus listener (reuse pattern from XWindowsPowerManager)
8. `EiScreen.cpp` - Implement `lockScreen()`

### Phase 5: Server Integration
1. `Server.h` - Add `m_lockAllScreens`, `m_lockOrigin`, handler declarations
2. `Server.cpp` constructor - Register event handlers for `screenLocked`/`screenUnlocked`
3. `Server.cpp` - Implement `handleScreenLockedEvent()`, `handleScreenUnlockedEvent()`
4. `Server.cpp` - Implement `broadcastScreenLock()` to all clients except origin
5. `Server.cpp` `processOptions()` - Handle `kOptionLockAllScreens`
6. `BaseClientProxy.h` - Add `virtual void screenLock(bool, const String&) = 0`
7. `ClientProxy1_0.cpp` - Implement `screenLock()` using `ProtocolUtil::writef()`
8. `Config.cpp` `readSectionOptions()` - Parse `lockAllScreens` after line 699

### Phase 6: Client Integration
1. `ServerProxy.cpp` `parseMessage()` - Add `kMsgDScreenLock` case after line 320
2. `ServerProxy.cpp` - Implement `screenLock()` parser method
3. `Client.h` - Declare `screenLock(bool, const String&)`
4. `Client.cpp` - Implement `screenLock()` with origin check and call to `m_screen->lockScreen()`

### Phase 7: GUI - Server Config
1. `ServerConfigDialogBase.ui` - Add checkbox in Advanced tab
2. `ServerConfig.h` - Add `m_LockAllScreens` member, getter/setter
3. `ServerConfig.cpp` `commit()` - Save setting
4. `ServerConfig.cpp` `recall()` - Load setting
5. `ServerConfig.cpp` `operator==` - Compare setting
6. `ServerConfig.cpp` `operator<<` - Output to config file
7. `ServerConfigDialog.cpp` - Initialize checkbox and connect signal

### Phase 8: Hotkey Action System
1. `Action.h` - Add `lockAllScreens` to `ActionType` enum (value = 10)
2. `Action.cpp` - Add `"lockAllScreens"` to string names array
3. `ActionDialogBase.ui` - Add radio button "Lock all screens"
4. `ActionDialog.cpp` - Wire up the new radio button to action type
5. `InputFilter.h` - Create `LockAllScreensAction` class:
   ```cpp
   class LockAllScreensAction : public Action {
   public:
     LockAllScreensAction(IEventQueue *events);
     virtual Action *clone() const;
     virtual String format() const;
     virtual void perform(const Event &event);
   private:
     IEventQueue *m_events;
   };
   ```
6. `InputFilter.cpp` - Implement `LockAllScreensAction::perform()`:
   - Create lock event info
   - Queue `lockAllScreens` event to server
7. `Server.h` - Add event type and info struct for lockAllScreens
8. `Server.cpp` - Handle lockAllScreens event → call `broadcastScreenLock()`
9. `Config.cpp` `parseAction()` - Parse `"lockAllScreens"` action:
   ```cpp
   else if (name == "lockAllScreens") {
     action = new InputFilter::LockAllScreensAction(m_events);
   }
   ```

---

## Loop Prevention

Each `kMsgDScreenLock` message includes the originating screen name. Clients ignore lock commands that originated from themselves. Additionally:
- `m_remoteLockPending` flag prevents echoing back remote-triggered locks
- Debounce timer (500ms) prevents rapid lock cycles

---

## Platform API Reference

### Windows
```cpp
// Include
#include <Wtsapi32.h>
#pragma comment(lib, "Wtsapi32.lib")  // Or add to CMakeLists.txt

// Registration (in constructor)
WTSRegisterSessionNotification(m_window, NOTIFY_FOR_THIS_SESSION);

// Unregistration (in destructor)
WTSUnRegisterSessionNotification(m_window);

// Detection (in onEvent switch)
case WM_WTSSESSION_CHANGE:
    switch (wParam) {
    case WTS_SESSION_LOCK:   // 0x7
        onSessionLockStateChange(true);
        break;
    case WTS_SESSION_UNLOCK: // 0x8
        onSessionLockStateChange(false);
        break;
    }
    return true;

// Trigger
LockWorkStation();  // Returns BOOL
```

### macOS
```cpp
// Detection - Darwin notifications (works at login window)
CFNotificationCenterRef center = CFNotificationCenterGetDarwinNotifyCenter();
CFNotificationCenterAddObserver(
    center,
    this,
    screenLockCallback,
    CFSTR("com.apple.screenIsLocked"),
    NULL,
    CFNotificationSuspensionBehaviorDeliverImmediately);
CFNotificationCenterAddObserver(
    center,
    this,
    screenLockCallback,
    CFSTR("com.apple.screenIsUnlocked"),
    NULL,
    CFNotificationSuspensionBehaviorDeliverImmediately);

// Cleanup
CFNotificationCenterRemoveObserver(center, this, CFSTR("com.apple.screenIsLocked"), NULL);
CFNotificationCenterRemoveObserver(center, this, CFSTR("com.apple.screenIsUnlocked"), NULL);

// Trigger - CGSession
system("/System/Library/CoreServices/Menu\\ Extras/User.menu/Contents/Resources/CGSession -suspend");
```

### Linux (X11/Wayland)
```cpp
// D-Bus infrastructure already exists in ArchSystemUnix.cpp
// Uses QtDBus - already linked via CMakeLists.txt

// Detection - D-Bus signals (try multiple services)
// Service 1: org.freedesktop.ScreenSaver
//   Signal: ActiveChanged(bool active)
// Service 2: org.gnome.SessionManager
//   Signal: SessionRunning / SessionOver
// Service 3: org.freedesktop.login1.Session
//   Signal: Lock / Unlock

// Trigger - try in order:
// 1. D-Bus: org.freedesktop.login1.Session.Lock()
// 2. D-Bus: org.gnome.ScreenSaver.Lock()
// 3. Fallback: system("loginctl lock-session")
```

---

## Potential Issues & Mitigations

### Windows
| Issue | Mitigation |
|-------|------------|
| `WTSRegisterSessionNotification` requires valid HWND | Register after window creation (line 151) |
| Duplicate lock events | Track state with `m_screenLocked` flag |
| Primary vs Secondary handling | Lock detection only on primary screen |

### macOS
| Issue | Mitigation |
|-------|------------|
| Notifications run on power thread | Use thread-safe `m_events->addEvent()` |
| Thread safety for shared state | Use existing `m_pmMutex` |
| Login window scenarios | Darwin notifications work system-wide |
| `setOptions()` is empty | Must implement from scratch |

### Linux
| Issue | Mitigation |
|-------|------------|
| D-Bus service not available | Graceful fallback, log warning |
| Multiple DE variations (GNOME/KDE/etc) | Try multiple D-Bus services in order |
| Static cookies not thread-safe | Single-threaded OK, add mutex if needed |
| X11 vs Wayland divergence | Separate implementations, shared D-Bus logic |
| EiScreen needs separate handling | Implement parallel to XWindowsScreen |

### Protocol
| Issue | Mitigation |
|-------|------------|
| Lock loops | Origin name in message + `m_remoteLockPending` flag |
| Rapid lock/unlock cycles | 500ms debounce timer |
| Version compatibility | Add to ClientProxy1_0 (works with all versions) |

---

## File Count Summary

| Category | Files | New | Modified |
|----------|-------|-----|----------|
| Core Infrastructure | 5 | 0 | 5 |
| Windows Platform | 3 | 0 | 3 |
| macOS Platform | 2 | 0 | 2 |
| Linux Platform | 4 | 0 | 4 |
| Server + Hotkey System | 7 | 0 | 7 |
| Client | 4 | 0 | 4 |
| GUI - Server Config | 4 | 0 | 4 |
| GUI - Hotkey Action | 4 | 0 | 4 |
| **Total** | **33** | **0** | **33** |

---

## Testing Considerations

1. **Per-platform testing first**
   - Windows: Lock via Win+L, verify event fires
   - macOS: Lock via Ctrl+Cmd+Q, verify notification received
   - Linux: Lock via loginctl, verify D-Bus signal received

2. **Cross-platform combinations**
   - Win→Mac, Win→Linux
   - Mac→Win, Mac→Linux
   - Linux→Win, Linux→Mac

3. **Edge cases**
   - Rapid lock/unlock cycles (test debounce)
   - Feature disabled (no propagation)
   - Reconnection after lock
   - Lock loops (should not occur)
   - D-Bus service unavailable on Linux
   - Login window scenarios on macOS

4. **Regression testing**
   - Existing screensaver functionality still works
   - Existing suspend/resume still works
   - No performance impact during normal operation
