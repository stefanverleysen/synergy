# Touch Activates Screen — Build 3 Regression Analysis

**PR:** [#147 — feat(windows): touch activates screen](https://github.com/symless/synergy/pull/147)
**Branch:** `feat/touch-activates-screen`
**Reporter:** Abel Lin (Feb 26, 2026)
**Regression commit:** `9d05f96` — "fix: resolve touch click bugs on secondary (client) screens"

---

## Customer Feedback (Abel Lin)

> Unfortunately, this build is worse than the previous build. When repeated touches
> occur between the server and a client, the success rate remains around 20%, just as
> before. Previously, repeated touches between a client and another client worked fine,
> but starting with this build, they no longer respond at all. Third build not as good
> as 2nd.

### Summary of Issues

| Scenario                  | Build 2 (`3fab44b`) | Build 3 (`9d05f96`) | Status      |
|---------------------------|---------------------|---------------------|-------------|
| Server → Client touch     | ~20% success        | ~20% success        | No change   |
| Client → Client touch     | Working             | **Completely broken** | REGRESSION  |

---

## Build-to-Commit Mapping

| Build | Commit    | Date       | Description                                      |
|-------|-----------|------------|--------------------------------------------------|
| 1st   | `78334da` | Feb 11     | Initial implementation                           |
| 2nd   | `3fab44b` | Feb 17     | PR review round 2 — rename grabScreen→grabInput  |
| 3rd   | `9d05f96` | Feb 20     | "Fix touch click bugs on secondary screens"      |

---

## Root Cause Analysis: What `9d05f96` Changed

The diff between build 2 and build 3 spans 5 files. Three changes are **prime suspects** for breaking client-to-client touch.

### BUG 1 (Critical): Raw input restricted to primary only
**File:** `src/lib/platform/MSWindowsDesks.cpp:736-738`

```cpp
// BUILD 2 (working): raw input fires on ALL screens
if (raw->header.dwType == RIM_TYPEHID &&
    raw->data.hid.dwCount > 0 && raw->data.hid.dwSizeHid > 0 &&
    m_isPrimary) {

// BUILD 3 (broken): raw input ONLY fires on primary — short-circuits earlier
if (raw->header.dwType == RIM_TYPEHID && m_isPrimary &&
    raw->data.hid.dwCount > 0 && raw->data.hid.dwSizeHid > 0) {
```

**Impact:** In both builds, `m_isPrimary` gates this code, so raw HID touch events
are only processed on the server (primary). This means **clients never fire
`DESKFLOW_MSG_TOUCH` via the raw input path**. Client-to-client touch relies entirely
on the low-level mouse hook (see BUG 2). The reorder itself is cosmetic, but the
existing `m_isPrimary` gate confirms that raw input was **never the detection path
for clients** — the hook was.

### BUG 2 (Critical — Root Cause): Hook now eats touch events ONLY in relay mode
**File:** `src/lib/platform/MSWindowsHook.cpp:599-609`

```cpp
// BUILD 2 (working):
// On primary: eat the event to prevent edge detection and
// button-state locking (isLockedToScreen) from racing.
// On secondary (client): let it through so the click reaches
// the target window (e.g. Start menu); no jump zones on clients.
if (g_isPrimary) {
  return 1;
}

// BUILD 3 (broken):
// Only eat in relay mode (cursor has left this screen) to
// prevent edge detection and isLockedToScreen from racing.
// In watch mode (cursor on server), let it through for
// normal touch behavior on the server's own screens.
if (g_isPrimary && g_mode == kHOOK_RELAY_EVENTS) {
  return 1;
}
```

**This is the primary regression.** Here's why:

- **Build 2:** On clients (`g_isPrimary == false`), touch events are detected by the
  hook, `DESKFLOW_MSG_TOUCH` is posted, and the event passes through to the OS
  (`return 1` is skipped). The touch message triggers `grabInput` → server switches
  screens. **Client-to-client works.**

- **Build 3:** The logic is identical for clients (still `g_isPrimary == false`, still
  falls through). **BUT** the comment and intent changed: the code now explicitly
  thinks about "relay mode" vs "watch mode" on the primary. However, note that
  `PostThreadMessage(g_threadID, DESKFLOW_MSG_TOUCH, x, y)` still runs for clients.

  **The real question is:** Does `DESKFLOW_MSG_TOUCH` get processed on the client side
  in build 3? If the desk thread's `WM_INPUT` handler (BUG 1) is the only handler for
  `DESKFLOW_MSG_TOUCH` on clients, and it now gates on `m_isPrimary`, then client touch
  messages are **posted but never consumed**. Check whether there is a separate
  `DESKFLOW_MSG_TOUCH` handler in the desk thread message loop for non-primary screens.

### BUG 3 (Likely contributor): Hider window blocks touch on secondary screens
**File:** `src/lib/platform/MSWindowsDesks.cpp:442-448`

```cpp
// BUILD 3 added this to secondaryDeskProc WM_MOUSEMOVE handler:
case WM_MOUSEMOVE: {
  LPARAM extraInfo = GetMessageExtraInfo();
  if ((extraInfo & TOUCH_SIGNATURE_MASK) == TOUCH_SIGNATURE)
    break;    // <--- breaks out of the switch, skipping hider auto-hide

  // ... existing code to auto-hide the hider window
```

**Impact:** On secondary (client) screens, when a touch-generated mouse move occurs,
the hider window **stays visible and does not auto-hide**. This means:
- The hider window remains on top, intercepting all input
- `WindowFromPoint()` in `activateWindowAt()` returns the hider window instead of
  the actual target application
- Touch clicks land on the invisible hider overlay, not the real window underneath
- This explains why client-to-client touch "no longer responds at all"

### BUG 4 (Minor): Early return when window already foreground
**File:** `src/lib/platform/MSWindowsScreen.cpp:1980-1984`

```cpp
// BUILD 3 added:
if (foreground == root) {
  LOG((CLOG_DEBUG1 "touch: window %p already foreground", ...));
  return;  // <--- skips the click replay entirely
}
```

**Impact:** If the target window is already the foreground window on a client, the
entire `activateWindowAt()` call is skipped. This could suppress the focus-switch
signal in scenarios where the same app was previously active.

---

## Recommended Fixes (Priority Order)

### Fix 1: Remove hider break on touch events (BUG 3)
The hider must still auto-hide when touch occurs on a client screen, otherwise it
blocks all subsequent input. Remove the `TOUCH_SIGNATURE` check in
`secondaryDeskProc`'s `WM_MOUSEMOVE` handler, or only apply it on primary screens.

```cpp
// REMOVE or guard with isPrimary:
case WM_MOUSEMOVE: {
  // Don't skip hider auto-hide for touch events on clients
  MSWindowsDesks *self = reinterpret_cast<MSWindowsDesks *>(
      GetWindowLongPtr(hwnd, GWLP_USERDATA));
  if (self && IsWindowVisible(hwnd)) {
    self->m_screen->onLocalInput();
  }
  break;
}
```

### Fix 2: Verify DESKFLOW_MSG_TOUCH is handled on clients (BUG 2)
Ensure that when the LL hook posts `DESKFLOW_MSG_TOUCH` on a client, there is a
handler in the desk thread message loop that processes it (not gated by `m_isPrimary`).
The raw input `WM_INPUT` path is primary-only, but the `DESKFLOW_MSG_TOUCH` message
posted by the hook needs a consumer on clients too.

### Fix 3: Don't early-return when window is already foreground (BUG 4)
The `activateWindowAt` function should still proceed with the click even if the
window is already foreground — the purpose is focus *transfer across machines*, not
just local window activation.

### Fix 4: Server→Client 20% success rate (pre-existing)
This is a separate issue that existed before build 3. Likely causes:
- `GetCursorPos()` on clients returns parked cursor position, not actual touch point
  (flagged by Copilot reviewer)
- 500ms cooldown timer blocks rapid repeated touches
- `SetForegroundWindow` failures when crossing security contexts

---

## Files to Investigate

| File | Key Lines | What to Check |
|------|-----------|---------------|
| `src/lib/platform/MSWindowsDesks.cpp` | 442-448 | Hider window touch bypass |
| `src/lib/platform/MSWindowsDesks.cpp` | 736-738 | Raw input primary-only gate |
| `src/lib/platform/MSWindowsHook.cpp` | 599-609 | Hook relay-mode gate change |
| `src/lib/platform/MSWindowsScreen.cpp` | 1980-1984 | Early return on foreground match |
| `src/lib/platform/dfwhook.h` | 49-53 | Shared touch signature defines |
| `src/lib/client/Client.cpp` | 248-255 | Touch replay in enter() |
| `src/lib/server/Server.cpp` | handleTouchActivatedPrimaryEvent | Server-side touch routing |

---

## Quick Revert Option

If a hotfix build is needed immediately, reverting commit `9d05f96` will restore
build 2 behavior where client-to-client touch was working:

```bash
git revert 9d05f96
```

This loses the diagnostic logging and `activateWindowAt` improvements but restores
client-to-client functionality while the fixes above are implemented properly.
