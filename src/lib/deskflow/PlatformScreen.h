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

#pragma once

#include "common/stdexcept.h"
#include "deskflow/ClientArgs.h"
#include "deskflow/DragInformation.h"
#include "deskflow/IPlatformScreen.h"

//! Base screen implementation
/*!
This screen implementation is the superclass of all other screen
implementations.  It implements a handful of methods and requires
subclasses to implement the rest.
*/
class PlatformScreen : public IPlatformScreen
{
public:
  PlatformScreen(
      IEventQueue *events, deskflow::ClientScrollDirection scrollDirection = deskflow::ClientScrollDirection::SERVER
  );
  virtual ~PlatformScreen();

  // IScreen overrides
  void *getEventTarget() const override = 0;
  bool getClipboard(ClipboardID id, IClipboard *) const override = 0;
  void getShape(SInt32 &x, SInt32 &y, SInt32 &width, SInt32 &height) const override = 0;
  void getCursorPos(SInt32 &x, SInt32 &y) const override = 0;

  // IPrimaryScreen overrides
  void reconfigure(UInt32 activeSides) override = 0;
  void warpCursor(SInt32 x, SInt32 y) override = 0;
  UInt32 registerHotKey(KeyID key, KeyModifierMask mask) override = 0;
  void unregisterHotKey(UInt32 id) override = 0;
  void fakeInputBegin() override = 0;
  void fakeInputEnd() override = 0;
  SInt32 getJumpZoneSize() const override = 0;
  bool isAnyMouseButtonDown(UInt32 &buttonID) const override = 0;
  void getCursorCenter(SInt32 &x, SInt32 &y) const override = 0;

  // ISecondaryScreen overrides
  void fakeMouseButton(ButtonID id, bool press) override = 0;
  void fakeMouseMove(SInt32 x, SInt32 y) override = 0;
  void fakeMouseRelativeMove(SInt32 dx, SInt32 dy) const override = 0;
  void fakeMouseWheel(SInt32 xDelta, SInt32 yDelta) const override = 0;

  // IKeyState overrides
  void updateKeyMap() override;
  void updateKeyState() override;
  void setHalfDuplexMask(KeyModifierMask) override;
  void fakeKeyDown(KeyID id, KeyModifierMask mask, KeyButton button, const String &) override;
  bool fakeKeyRepeat(KeyID id, KeyModifierMask mask, SInt32 count, KeyButton button, const String &lang) override;
  bool fakeKeyUp(KeyButton button) override;
  void fakeAllKeysUp() override;
  bool fakeCtrlAltDel() override;
  bool isKeyDown(KeyButton) const override;
  KeyModifierMask getActiveModifiers() const override;
  KeyModifierMask pollActiveModifiers() const override;
  SInt32 pollActiveGroup() const override;
  void pollPressedKeys(KeyButtonSet &pressedKeys) const override;
  void clearStaleModifiers() override;

  void setDraggingStarted(bool started) override
  {
    m_draggingStarted = started;
  }
  bool isDraggingStarted() override;
  bool isFakeDraggingStarted() override
  {
    return m_fakeDraggingStarted;
  }
  String &getDraggingFilename() override
  {
    return m_draggingFilename;
  }
  void clearDraggingFilename() override
  {
  }

  // IPlatformScreen overrides
  void enable() override = 0;
  void disable() override = 0;
  void enter() override = 0;
  bool canLeave() override = 0;
  void leave() override = 0;
  bool setClipboard(ClipboardID, const IClipboard *) override = 0;
  void checkClipboards() override = 0;
  void openScreensaver(bool notify) override = 0;
  void closeScreensaver() override = 0;
  void screensaver(bool activate) override = 0;
  void resetOptions() override = 0;
  void setOptions(const OptionsList &options) override = 0;
  void setSequenceNumber(UInt32) override = 0;
  bool isPrimary() const override = 0;

  void fakeDraggingFiles(DragFileList fileList) override
  {
    throw std::runtime_error("fakeDraggingFiles not implemented");
  }
  const String &getDropTarget() const override
  {
    throw std::runtime_error("getDropTarget not implemented");
  }

  void runScript(const String &name, const String &content) override;

protected:
  //! Update mouse buttons
  /*!
  Subclasses must implement this method to update their internal mouse
  button mapping and, if desired, state tracking.
  */
  virtual void updateButtons() = 0;

  //! Get the key state
  /*!
  Subclasses must implement this method to return the platform specific
  key state object that each subclass must have.
  */
  virtual IKeyState *getKeyState() const = 0;

  // IPlatformScreen overrides
  void handleSystemEvent(const Event &event, void *) override = 0;

  /*!
   * \brief mapClientScrollDirection
   * Convert scroll according to client scroll directio
   * \return converted value according to the client scroll direction
   */
  virtual SInt32 mapClientScrollDirection(SInt32) const;

protected:
  String m_draggingFilename;
  bool m_draggingStarted;
  bool m_fakeDraggingStarted;

private:
  /*!
   * \brief m_clientScrollDirection
   * This member contains client scroll direction.
   * This member is used only on client side.
   */
  deskflow::ClientScrollDirection m_clientScrollDirection = deskflow::ClientScrollDirection::SERVER;
};
