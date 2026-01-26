see# Custom Script Actions Feature

## Overview

Allow hotkeys to execute custom scripts or commands on specific clients. Each client can have its own platform-specific script (PowerShell on Windows, bash on macOS/Linux) for the same action name.

## Requirements

- **Hotkey Trigger**: Define hotkey that triggers a named script action
- **Per-Client Scripts**: Each client specifies its own script for a given action name
- **Cross-Platform**: Windows (PowerShell/bat), macOS (bash/zsh), Linux (bash)
- **Client-Side Storage**: Scripts live on each client machine (not synced from server)
- **Security**: Clients control their own scripts - no arbitrary code injection from server

---

## Architecture

### How It Works

```
[User presses hotkey on any screen]
    |
    v
[Server detects hotkey via InputFilter]
    |
    v
[Server broadcasts kMsgDRunScript to all clients]
    |-- scriptName: "backup"
    |-- targetScreens: ["laptop", "desktop"] (optional, empty = all)
    |
    v
[Each client receives message]
    |
    v
[Client looks up local script path for "backup"]
    |-- Windows: C:\Users\X\.deskflow\scripts\backup.ps1
    |-- macOS:   ~/.deskflow/scripts/backup.sh
    |-- Linux:   ~/.deskflow/scripts/backup.sh
    |
    v
[Client executes script locally]
```

### Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Script storage | Client-side | Security - no remote code execution |
| Script reference | By name | Server just says "run backup", client decides how |
| Targeting | Optional screen filter | Can run on all or specific clients |
| Execution | Async/fire-and-forget | Don't block hotkey processing |

---

## Implementation Plan

### Phase 1: Core Infrastructure

#### 1.1 Protocol Message

**File: `src/lib/deskflow/protocol_types.h`**
```cpp
// Add after kMsgDScreenWake (line ~295)
extern const char *const kMsgDRunScript;
```

**File: `src/lib/deskflow/protocol_types.cpp`**
```cpp
// Add message definition
// %s = script name
const char *const kMsgDRunScript = "DRSC%s";
```

#### 1.2 New Action Type

**File: `src/gui/src/Action.h`**
```cpp
// Add to ActionType enum (after lockAllScreens)
runScript
```

**File: `src/gui/src/Action.cpp`**
```cpp
// Add to m_ActionTypeNames array
"runScript"

// Add to text() method - handle script name parameter
case runScript:
    text += "(";
    text += m_scriptName;
    text += ")";
    break;
```

#### 1.3 Server-Side Action Class

**File: `src/lib/server/InputFilter.h`**
```cpp
// Add after LockAllScreensAction class
class RunScriptAction : public Action {
public:
    RunScriptAction(IEventQueue *events, const String &scriptName);
    RunScriptAction(RunScriptAction const &) = default;
    virtual ~RunScriptAction() = default;

    virtual Action *clone() const override;
    virtual String format() const override;
    virtual void perform(const Event &event) override;

private:
    String m_scriptName;
    IEventQueue *m_events;
};
```

**File: `src/lib/server/InputFilter.cpp`**
```cpp
// Implement RunScriptAction
InputFilter::RunScriptAction::RunScriptAction(
    IEventQueue *events, const String &scriptName)
    : m_events(events), m_scriptName(scriptName)
{
}

InputFilter::Action *InputFilter::RunScriptAction::clone() const
{
    return new RunScriptAction(*this);
}

String InputFilter::RunScriptAction::format() const
{
    return deskflow::string::sprintf("runScript(%s)", m_scriptName.c_str());
}

void InputFilter::RunScriptAction::perform(const Event &event)
{
    // Queue event to server to broadcast script execution
    m_events->addEvent(Event(
        m_events->forServer().runScript(),
        event.getTarget(),
        new Server::RunScriptInfo(m_scriptName)
    ));
}
```

#### 1.4 Config Parsing

**File: `src/lib/server/Config.cpp`**
```cpp
// In parseAction() function, add after lockAllScreens handling (~line 1106)
else if (name == "runScript") {
    if (args.size() != 1) {
        throw XConfigRead(*this, "runScript requires exactly one argument");
    }
    action = new InputFilter::RunScriptAction(m_events, args[0]);
}
```

---

### Phase 2: Server Broadcasting

#### 2.1 Server Event Types

**File: `src/lib/base/EventTypes.h`**
```cpp
// Add to IServerEvents class (after lockAllScreens)
Event::Type runScript() const;
```

**File: `src/lib/base/EventTypes.cpp`**
```cpp
// Register event
REGISTER_EVENT(IServer, runScript)
```

#### 2.2 Server RunScript Info

**File: `src/lib/server/Server.h`**
```cpp
// Add info struct (after LockAllScreensInfo if it exists, or in public section)
class RunScriptInfo {
public:
    RunScriptInfo(const String &scriptName) : m_scriptName(scriptName) {}
    String m_scriptName;
};

// Add handler declaration
void handleRunScriptEvent(const Event &event, void *);
```

**File: `src/lib/server/Server.cpp`**
```cpp
// In constructor, register event handler
m_events->adoptHandler(
    m_events->forServer().runScript(),
    this,
    new TMethodEventJob<Server>(this, &Server::handleRunScriptEvent)
);

// Implement handler
void Server::handleRunScriptEvent(const Event &event, void *)
{
    RunScriptInfo *info = static_cast<RunScriptInfo *>(event.getData());
    if (info == nullptr) {
        return;
    }

    LOG((CLOG_DEBUG "broadcasting run script: %s", info->m_scriptName.c_str()));

    // Send to all clients
    for (auto &entry : m_clients) {
        BaseClientProxy *client = entry.second;
        client->runScript(info->m_scriptName);
    }

    delete info;
}
```

#### 2.3 Client Proxy

**File: `src/lib/server/BaseClientProxy.h`**
```cpp
// Add virtual method
virtual void runScript(const String &scriptName) = 0;
```

**File: `src/lib/server/ClientProxy1_0.h`**
```cpp
// Declare override
virtual void runScript(const String &scriptName) override;
```

**File: `src/lib/server/ClientProxy1_0.cpp`**
```cpp
void ClientProxy1_0::runScript(const String &scriptName)
{
    LOG((CLOG_DEBUG1 "send run script \"%s\" to \"%s\"",
         scriptName.c_str(), getName().c_str()));
    ProtocolUtil::writef(getStream(), kMsgDRunScript, &scriptName);
}
```

**File: `src/lib/server/PrimaryClient.h`**
```cpp
virtual void runScript(const String &scriptName) override;
```

**File: `src/lib/server/PrimaryClient.cpp`**
```cpp
void PrimaryClient::runScript(const String &scriptName)
{
    // Execute locally on primary/server
    m_screen->runScript(scriptName);
}
```

---

### Phase 3: Client-Side Execution

#### 3.1 Server Proxy (Client receives message)

**File: `src/lib/client/ServerProxy.h`**
```cpp
// Add private method declaration
void runScript();
```

**File: `src/lib/client/ServerProxy.cpp`**
```cpp
// In parseMessage(), add case for kMsgDRunScript
else if (memcmp(code, kMsgDRunScript, 4) == 0) {
    runScript();
}

// Implement parser
void ServerProxy::runScript()
{
    String scriptName;
    ProtocolUtil::readf(m_stream, kMsgDRunScript + 4, &scriptName);
    LOG((CLOG_DEBUG1 "recv run script: %s", scriptName.c_str()));
    m_client->runScript(scriptName);
}
```

#### 3.2 Client

**File: `src/lib/client/Client.h`**
```cpp
void runScript(const String &scriptName);
```

**File: `src/lib/client/Client.cpp`**
```cpp
void Client::runScript(const String &scriptName)
{
    LOG((CLOG_DEBUG "running script: %s", scriptName.c_str()));
    m_screen->runScript(scriptName);
}
```

#### 3.3 Platform Screen Interface

**File: `src/lib/deskflow/IPlatformScreen.h`**
```cpp
// Add virtual method
virtual void runScript(const String &scriptName) = 0;
```

**File: `src/lib/deskflow/Screen.h`**
```cpp
void runScript(const String &scriptName);
```

**File: `src/lib/deskflow/Screen.cpp`**
```cpp
void Screen::runScript(const String &scriptName)
{
    m_screen->runScript(scriptName);
}
```

---

### Phase 4: Platform-Specific Implementation

#### 4.1 Windows

**File: `src/lib/platform/MSWindowsScreen.h`**
```cpp
// Add method declaration
virtual void runScript(const String &scriptName) override;

// Add member for script base path
String m_scriptPath;
```

**File: `src/lib/platform/MSWindowsScreen.cpp`**
```cpp
void MSWindowsScreen::runScript(const String &scriptName)
{
    LOG((CLOG_DEBUG "running script on Windows: %s", scriptName.c_str()));

    // Build script path
    // Default: %APPDATA%\Deskflow\scripts\<scriptName>.ps1
    String scriptDir = getenv("APPDATA");
    scriptDir += "\\Deskflow\\scripts\\";

    // Try .ps1 first, then .bat, then .cmd
    String extensions[] = {".ps1", ".bat", ".cmd"};
    String scriptPath;

    for (const auto &ext : extensions) {
        scriptPath = scriptDir + scriptName + ext;
        if (GetFileAttributesA(scriptPath.c_str()) != INVALID_FILE_ATTRIBUTES) {
            break;
        }
        scriptPath.clear();
    }

    if (scriptPath.empty()) {
        LOG((CLOG_WARN "script not found: %s", scriptName.c_str()));
        return;
    }

    // Execute based on extension
    String command;
    if (scriptPath.find(".ps1") != String::npos) {
        command = "powershell.exe -ExecutionPolicy Bypass -File \"" + scriptPath + "\"";
    } else {
        command = "cmd.exe /c \"" + scriptPath + "\"";
    }

    LOG((CLOG_DEBUG "executing: %s", command.c_str()));

    // Run async - don't block
    STARTUPINFOA si = {sizeof(si)};
    PROCESS_INFORMATION pi;
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;

    if (CreateProcessA(
        NULL,
        const_cast<char*>(command.c_str()),
        NULL, NULL, FALSE,
        CREATE_NO_WINDOW,
        NULL, NULL, &si, &pi)) {
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);
    } else {
        LOG((CLOG_ERR "failed to execute script: %d", GetLastError()));
    }
}
```

#### 4.2 macOS

**File: `src/lib/platform/OSXScreen.h`**
```cpp
virtual void runScript(const String &scriptName) override;
```

**File: `src/lib/platform/OSXScreen.mm`**
```cpp
void OSXScreen::runScript(const String &scriptName)
{
    LOG((CLOG_DEBUG "running script on macOS: %s", scriptName.c_str()));

    // Build script path: ~/.deskflow/scripts/<scriptName>.sh
    String home = getenv("HOME");
    String scriptPath = home + "/.deskflow/scripts/" + scriptName + ".sh";

    // Check if exists
    struct stat st;
    if (stat(scriptPath.c_str(), &st) != 0) {
        LOG((CLOG_WARN "script not found: %s", scriptPath.c_str()));
        return;
    }

    // Execute async
    String command = "/bin/bash \"" + scriptPath + "\" &";
    LOG((CLOG_DEBUG "executing: %s", command.c_str()));
    system(command.c_str());
}
```

#### 4.3 Linux X11

**File: `src/lib/platform/XWindowsScreen.h`**
```cpp
virtual void runScript(const String &scriptName) override;
```

**File: `src/lib/platform/XWindowsScreen.cpp`**
```cpp
void XWindowsScreen::runScript(const String &scriptName)
{
    LOG((CLOG_DEBUG "running script on Linux: %s", scriptName.c_str()));

    // Build script path: ~/.deskflow/scripts/<scriptName>.sh
    String home = getenv("HOME");
    String scriptPath = home + "/.deskflow/scripts/" + scriptName + ".sh";

    // Check if exists
    struct stat st;
    if (stat(scriptPath.c_str(), &st) != 0) {
        LOG((CLOG_WARN "script not found: %s", scriptPath.c_str()));
        return;
    }

    // Execute async using fork
    pid_t pid = fork();
    if (pid == 0) {
        // Child process
        execl("/bin/bash", "bash", scriptPath.c_str(), (char*)NULL);
        _exit(1);
    } else if (pid < 0) {
        LOG((CLOG_ERR "fork failed: %s", strerror(errno)));
    }
    // Parent continues immediately (async)
}
```

#### 4.4 Linux Wayland (EiScreen)

**File: `src/lib/platform/EiScreen.h`**
```cpp
virtual void runScript(const String &scriptName) override;
```

**File: `src/lib/platform/EiScreen.cpp`**
```cpp
void EiScreen::runScript(const String &scriptName)
{
    // Same as XWindowsScreen - can share implementation via helper
    LOG((CLOG_DEBUG "running script on Wayland: %s", scriptName.c_str()));

    String home = getenv("HOME");
    String scriptPath = home + "/.deskflow/scripts/" + scriptName + ".sh";

    struct stat st;
    if (stat(scriptPath.c_str(), &st) != 0) {
        LOG((CLOG_WARN "script not found: %s", scriptPath.c_str()));
        return;
    }

    pid_t pid = fork();
    if (pid == 0) {
        execl("/bin/bash", "bash", scriptPath.c_str(), (char*)NULL);
        _exit(1);
    }
}
```

---

### Phase 5: GUI Integration

#### 5.1 Action Dialog

**File: `src/gui/src/ActionDialogBase.ui`**
- Add radio button "Run script"
- Add text input for script name

**File: `src/gui/src/ActionDialog.cpp`**
```cpp
// Add handling for runScript radio button
// When selected, enable script name input field
// Save script name to Action object
```

#### 5.2 Action Class Updates

**File: `src/gui/src/Action.h`**
```cpp
// Add member
QString m_scriptName;

// Add getter/setter
QString scriptName() const { return m_scriptName; }
void setScriptName(const QString &name) { m_scriptName = name; }
```

**File: `src/gui/src/Action.cpp`**
```cpp
// Update text() to include script name for runScript type
case runScript:
    return QString("%1(%2)").arg(m_ActionTypeNames[type()]).arg(m_scriptName);
```

---

## Configuration Example

### Server Config (synergy.conf)
```ini
section: options
    # Hotkey bindings
    keystroke(Control+Alt+b) = runScript(backup)
    keystroke(Control+Alt+s) = runScript(screenshot)
    keystroke(Control+Alt+r) = runScript(restart)
end
```

### Client Script Locations

| Platform | Script Directory |
|----------|-----------------|
| Windows | `%APPDATA%\Deskflow\scripts\` |
| macOS | `~/.deskflow/scripts/` |
| Linux | `~/.deskflow/scripts/` |

### Example Scripts

**Windows: `%APPDATA%\Deskflow\scripts\backup.ps1`**
```powershell
# Backup script for Windows
$date = Get-Date -Format "yyyy-MM-dd"
Copy-Item -Recurse "C:\Important" "D:\Backups\$date"
```

**macOS/Linux: `~/.deskflow/scripts/backup.sh`**
```bash
#!/bin/bash
# Backup script for Unix
date=$(date +%Y-%m-%d)
rsync -av ~/Important /backup/$date/
```

---

## Future Enhancements

### Optional: Target Specific Screens
```ini
# Only run on specific clients
keystroke(Control+Alt+b) = runScript(backup, laptop:desktop)
```

### Optional: Script Arguments
```ini
# Pass arguments to script
keystroke(Control+Alt+b) = runScript(backup, --full)
```

### Optional: GUI Script Manager
- List/add/edit/delete scripts from GUI
- Script editor with syntax highlighting
- Test button to run script manually

### Optional: Script Sync
- Option to sync scripts from server to clients
- Requires security considerations (signing, approval)

---

## File Summary

| File | Changes |
|------|---------|
| `src/lib/deskflow/protocol_types.h` | Add kMsgDRunScript |
| `src/lib/deskflow/protocol_types.cpp` | Define kMsgDRunScript |
| `src/lib/deskflow/IPlatformScreen.h` | Add runScript() |
| `src/lib/deskflow/Screen.h/cpp` | Add runScript() wrapper |
| `src/lib/base/EventTypes.h/cpp` | Add runScript event |
| `src/lib/server/InputFilter.h/cpp` | Add RunScriptAction class |
| `src/lib/server/Config.cpp` | Parse runScript action |
| `src/lib/server/Server.h/cpp` | Handle runScript event, broadcast |
| `src/lib/server/BaseClientProxy.h` | Add runScript() virtual |
| `src/lib/server/ClientProxy1_0.h/cpp` | Implement runScript() |
| `src/lib/server/PrimaryClient.h/cpp` | Implement runScript() |
| `src/lib/client/ServerProxy.h/cpp` | Parse runScript message |
| `src/lib/client/Client.h/cpp` | Forward to screen |
| `src/lib/platform/MSWindowsScreen.h/cpp` | Windows script execution |
| `src/lib/platform/OSXScreen.h/mm` | macOS script execution |
| `src/lib/platform/XWindowsScreen.h/cpp` | Linux X11 script execution |
| `src/lib/platform/EiScreen.h/cpp` | Linux Wayland script execution |
| `src/gui/src/Action.h/cpp` | Add runScript type |
| `src/gui/src/ActionDialog.cpp` | UI for script name |
| `src/gui/src/ActionDialogBase.ui` | Add radio button + input |

**Total: ~20 files modified**

---

## Security Considerations

1. **No Remote Code Execution**: Server only sends script NAME, not code
2. **Client Controls Scripts**: Users must manually create scripts on each client
3. **No Auto-Sync**: Scripts don't transfer over network (by default)
4. **Path Restrictions**: Scripts must be in designated directory
5. **Execution Policy**: Windows respects PowerShell execution policy
6. **File Permissions**: Unix scripts require execute permission

---

## Testing Plan

1. **Unit Tests**
   - Config parsing for runScript action
   - Action serialization/deserialization
   - Protocol message encoding/decoding

2. **Integration Tests**
   - Hotkey triggers script on server
   - Message broadcasts to all clients
   - Each platform executes correct script

3. **Manual Testing Matrix**
   | Server | Client | Test |
   |--------|--------|------|
   | Windows | Windows | PowerShell script runs |
   | Windows | macOS | Bash script runs |
   | Windows | Linux | Bash script runs |
   | macOS | Windows | PowerShell script runs |
   | macOS | macOS | Bash script runs |
   | Linux | Windows | PowerShell script runs |
