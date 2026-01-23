# Touch Input Local Feature - Implementation Plan

## Problem Statement

When using Synergy/Deskflow on a Windows touchscreen device (e.g., Surface Pro) as the server, touch input gets forwarded to client machines when the cursor is on their screen. This is problematic because:

1. Touch gestures (scrolling, tapping) on the server's physical screen affect the client instead of the local machine
2. Users expect touch input on their physical screen to control that screen, not a remote one
3. There's no way to use the touchscreen locally while the cursor is "visiting" another machine

## Research Findings

### How Touch Input Works on Windows

1. **WM_POINTER Messages (Windows 8+)**: Modern touch input comes through `WM_POINTERDOWN`, `WM_POINTERUP`, `WM_POINTERUPDATE` messages. These can be identified as touch vs. mouse using `GetPointerType()`.

2. **Touch-to-Mouse Conversion**: Windows automatically converts touch input to mouse messages for compatibility. These converted messages have a signature in `dwExtraInfo`:
   - Touch signature mask: `0xFFFFFF00`
   - Touch signature value: `0xFF515700`

3. **Low-Level Mouse Hook**: Synergy uses a low-level mouse hook (`WH_MOUSE_LL`) to capture and forward mouse events. Touch-generated mouse events pass through this hook.

### Key Files in Codebase

- `src/lib/platform/MSWindowsHook.cpp/h` - Low-level mouse/keyboard hooks
- `src/lib/platform/MSWindowsScreen.cpp/h` - Windows screen implementation
- `src/lib/deskflow/option_types.h` - Option ID definitions
- `src/lib/server/Config.cpp` - Server configuration parsing
- `src/gui/src/ServerConfig.cpp/h` - GUI server configuration
- `src/gui/src/ServerConfigDialog.cpp` - GUI settings dialog
- `src/gui/src/ServerConfigDialogBase.ui` - Qt UI form

## Implementation Approach

### Two-Layer Detection Strategy

Since touch input flows through two paths, we handle both:

1. **WM_POINTER Handler** (MSWindowsScreen): Catches touch at the source before conversion
2. **Mouse Hook Filter** (MSWindowsHook): Catches touch-converted mouse events using `dwExtraInfo` signature

### Implementation Steps

#### 1. Add Option Definition
**File**: `src/lib/deskflow/option_types.h`
- Add `kOptionTouchInputLocal` with code `"TILC"`

#### 2. Update Server Config Parsing
**File**: `src/lib/server/Config.cpp`
- Parse `touchInputLocal` boolean option in `readSectionOptions()`
- Add to `getOptionName()` and `getOptionValue()` for serialization

#### 3. Add GUI Configuration
**Files**: `ServerConfig.cpp/h`, `ServerConfigDialog.cpp`, `ServerConfigDialogBase.ui`
- Add `m_TouchInputLocal` member variable
- Add checkbox "Keep touch input on this computer" in Advanced options
- Wire up save/load/commit for the setting
- Support locking the setting via policy

#### 4. Implement Hook-Level Filtering
**File**: `src/lib/platform/MSWindowsHook.cpp/h`
- Add `g_touchInputLocal` and `g_isOnScreen` static flags
- Add `setTouchInputLocal()` and `setIsOnScreen()` methods
- In `mouseLLHook()`:
  - Check if event has touch signature in `dwExtraInfo`
  - If touchInputLocal enabled AND cursor is off-screen AND event is touch-generated:
    - Skip forwarding to client (don't call `mouseHookHandler`)
    - Let event proceed locally (call `CallNextHookEx`)

#### 5. Implement Screen-Level Handling
**File**: `src/lib/platform/MSWindowsScreen.cpp/h`
- Add WM_POINTER message constants for Windows 7 compatibility
- Dynamically load `GetPointerType()` API
- Add `m_touchInputLocal` and `m_lastInputWasTouch` members
- Handle `WM_POINTERDOWN/UP/UPDATE` messages
- Call `m_hook.setIsOnScreen()` in `enter()` and `leave()`
- Process `kOptionTouchInputLocal` in `setOptions()`

## Behavior Summary

When "Keep touch input on this computer" is enabled:

| Cursor Location | Input Type | Result |
|----------------|------------|--------|
| Local screen | Mouse | Normal - forwarded if needed |
| Local screen | Touch | Normal - works locally |
| Client screen | Mouse | Normal - forwarded to client |
| Client screen | Touch | **Kept local** - works on server screen |

## Testing Considerations

1. Test on touchscreen Windows device (Surface, touch laptop, etc.)
2. Verify touch stays local when cursor is on client screen
3. Verify mouse still forwards correctly to client
4. Verify setting persists across restarts
5. Test with Windows 7 (no pointer API) - should degrade gracefully
6. Verify checkbox appears in Server Configuration > Advanced

## Files Modified

```
src/gui/src/ServerConfig.cpp          # Config storage
src/gui/src/ServerConfig.h            # Config member
src/gui/src/ServerConfigDialog.cpp    # Dialog wiring
src/gui/src/ServerConfigDialogBase.ui # Checkbox UI
src/lib/deskflow/option_types.h       # Option ID
src/lib/platform/MSWindowsHook.cpp    # Hook filtering
src/lib/platform/MSWindowsHook.h      # Hook interface
src/lib/platform/MSWindowsScreen.cpp  # Screen handling
src/lib/platform/MSWindowsScreen.h    # Screen members
src/lib/server/Config.cpp             # Config parsing
```
