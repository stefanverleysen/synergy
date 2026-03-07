/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.symless.synergy.services.keyboard.actions

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import kotlin.math.max
import kotlin.math.min
import org.symless.synergy.BuildConfig
import org.symless.synergy.client.events.KeyboardEvent
import org.symless.synergy.client.models.keys.KeyModifierMask
import org.symless.synergy.client.util.Keyboard
import org.symless.synergy.client.util.logging.KLoggingManager
import org.symless.synergy.services.VirtualKeyboardService
import org.symless.synergy.services.keyboard.KeyboardEditHistory

typealias VirtualKeyboardActionCallable =
  (
    ic: InputConnection,
    et: ExtractedText?,
    specialKey: Keyboard.SpecialKey?,
    mods: KeyModifierMask,
    KeyboardEvent,
    KeyboardEditHistory?,
    VirtualKeyboardService,
  ) -> Unit

private val log = KLoggingManager.logger("VirtualKeyboardAction")

val editActionSelectAll: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    if (et != null) {
      ic.setSelection(0, et.text.length)
    } else {
      log.warn { "ExtractedText is null, cannot select all" }
    }
  }

val editActionCopy: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    if (et == null) {
      log.warn { "ExtractedText is null, cannot copy" }
    } else if (et.selectionStart != et.selectionEnd) {
      val selectedText = et.text.subSequence(et.selectionStart, et.selectionEnd)
      log.debug { "Copying selected text: $selectedText" }
      service.setClipboardText(selectedText)
    } else {
      log.warn { "No text selected to copy" }
    }
  }

val editActionCut: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    if (et == null) {
      log.warn { "ExtractedText is null, cannot cut" }
    } else if (et.selectionStart != et.selectionEnd) {
      val selectedText = et.text.subSequence(et.selectionStart, et.selectionEnd)
      log.debug { "Cutting selected text: $selectedText" }
      service.setClipboardText(selectedText)
      ic.commitText("", 1)
      service.saveEditHistory(
        service.editorInfo,
        service.currentExtractedTest(),
      )
    } else {
      log.warn { "No text selected to cut" }
    }
  }

val editActionPaste: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    val cbText = service.getClipboardText()
    if (cbText != null) {
      log.debug { "Pasting clipboard text: $cbText" }
      ic.commitText(cbText, 1)
      service.saveEditHistory(
        service.editorInfo,
        service.currentExtractedTest(),
      )
    } else {
      log.warn { "Clipboard is empty, nothing to paste" }
    }
  }

val editActionLeft: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    if (et == null) {
      log.warn { "ExtractedText is null, cannot handle left arrow" }
    } else if (mods.isShift) {
      log.debug { "Extend selection left" }
      val selStart =
        when {
          mods.isMeta || mods.isSuper -> 0
          else -> max(0, et.selectionStart - 1)
        }
      val selEnd = et.selectionEnd
      log.debug {
        "Selection(start=$selStart,end=$selEnd),Previous(start=${et.selectionStart},end=${et.selectionEnd})"
      }
      ic.setSelection(selStart, selEnd)
    } else if (mods.isMeta || mods.isSuper) {
      log.debug { "Move cursor to start of line" }
      ic.setSelection(0, 0)
    } else {
      ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
      ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT))
    }
  }

val editActionRight: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    if (et == null) {
      log.warn { "ExtractedText is null, cannot handle right arrow" }
    } else if (mods.isShift) {
      log.debug { "Extend selection right" }
      val text = et.text
      val selStart = et.selectionStart
      val selEnd =
        when {
          mods.isMeta || mods.isSuper -> text.length
          else -> min(text.length, et.selectionEnd + 1)
        }
      log.debug {
        "Selection(start=$selStart,end=$selEnd),Previous(start=${et.selectionStart},end=${et.selectionEnd})"
      }
      ic.setSelection(selStart, selEnd)
    } else if (mods.isMeta || mods.isSuper) {
      log.debug { "Move cursor to end of line" }
      val text = et.text
      ic.setSelection(text.length, text.length)
    } else {
      ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
      ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
    }
  }

val editActionUp: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_UP))
  }

val editActionDown: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
  }

val editActionHome: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MOVE_HOME))
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MOVE_HOME))
  }

val editActionEnd: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MOVE_END))
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MOVE_END))
  }

val editActionPageUp: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_UP))
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_PAGE_UP))
  }

val editActionPageDown: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN))
    ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_PAGE_DOWN))
  }

val editActionBackSpace: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    log.debug { "BackSpace key detected" }

    if (BuildConfig.DEBUG) service.logCurrentImeState()

    if (et != null && et.selectionStart != et.selectionEnd) {
      ic.commitText("", 1)
    } else {
      ic.deleteSurroundingText(1, 0)
    }
    service.saveEditHistory(service.editorInfo, service.currentExtractedTest())
  }

val editActionDelete: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    log.debug { "Delete detected" }

    ic.deleteSurroundingText(0, 1)
    service.saveEditHistory(service.editorInfo, service.currentExtractedTest())
  }

val editActionUndo: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    if (et == null) {
      log.warn { "ExtractedText is null, cannot undo" }
    } else {
      val undoValue = editHistory?.undo() // et.text.toString()
      log.debug { "Undo value=$undoValue" }
      if (undoValue != null) {
        ic.beginBatchEdit()
        ic.setSelection(0, et.text.length)
        ic.deleteSurroundingText(et.text.length, 0)
        ic.commitText(undoValue, undoValue.length)
        ic.endBatchEdit()
      }
    }
  }

val editActionRedo: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    if (et == null) {
      log.warn { "ExtractedText is null, cannot redo" }
    } else {
      val redoValue = editHistory?.redo()
      log.debug { "Redo value=$redoValue" }
      if (redoValue != null) {
        ic.beginBatchEdit()
        ic.setSelection(0, et.text.length)
        ic.deleteSurroundingText(et.text.length, 0)
        ic.commitText(redoValue, redoValue.length)
        ic.endBatchEdit()
      }
    }
  }

val editActionInsertNewline: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    log.debug { "Inserting newline (explicit)" }
    ic.commitText("\n", 1)
    service.saveEditHistory(service.editorInfo, service.currentExtractedTest())
  }

val editActionSendControlEnter: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    log.debug { "Sending Ctrl+Enter keycode" }

    // Send Control modifier down
    ic.sendKeyEvent(
      android.view.KeyEvent(
        android.view.KeyEvent.ACTION_DOWN,
        android.view.KeyEvent.KEYCODE_CTRL_LEFT
      )
    )

    // Send Enter down with Control meta state
    ic.sendKeyEvent(
      android.view.KeyEvent(
        0, 0,
        android.view.KeyEvent.ACTION_DOWN,
        android.view.KeyEvent.KEYCODE_ENTER,
        0,
        android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
      )
    )

    // Send Enter up with Control meta state
    ic.sendKeyEvent(
      android.view.KeyEvent(
        0, 0,
        android.view.KeyEvent.ACTION_UP,
        android.view.KeyEvent.KEYCODE_ENTER,
        0,
        android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
      )
    )

    // Send Control modifier up
    ic.sendKeyEvent(
      android.view.KeyEvent(
        android.view.KeyEvent.ACTION_UP,
        android.view.KeyEvent.KEYCODE_CTRL_LEFT
      )
    )
  }

val editActionHandleReturn: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    val editorInfo = service.editorInfo
    if (editorInfo != null) {
      val imeAction = editorInfo.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION

      // Check if the app explicitly wants Return to insert newline instead of action
      val noEnterAction = (editorInfo.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

      if (noEnterAction) {
        log.info { "Handle Return: IME_FLAG_NO_ENTER_ACTION set for ${editorInfo.packageName}, inserting newline" }
        ic.commitText("\n", 1)
        service.saveEditHistory(service.editorInfo, service.currentExtractedTest())
      } else {
        when (imeAction) {
          android.view.inputmethod.EditorInfo.IME_ACTION_GO,
          android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH,
          android.view.inputmethod.EditorInfo.IME_ACTION_SEND,
          android.view.inputmethod.EditorInfo.IME_ACTION_DONE,
          android.view.inputmethod.EditorInfo.IME_ACTION_NEXT,
          android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS -> {
            log.info { "Handle Return: Performing IME action $imeAction for ${editorInfo.packageName}" }
            ic.performEditorAction(imeAction)
          }
          else -> {
            log.info { "Handle Return: No specific action for ${editorInfo.packageName}, inserting newline" }
            ic.commitText("\n", 1)
            service.saveEditHistory(service.editorInfo, service.currentExtractedTest())
          }
        }
      }
    } else {
      log.warn { "EditorInfo is null, inserting newline as fallback" }
      ic.commitText("\n", 1)
      service.saveEditHistory(service.editorInfo, service.currentExtractedTest())
    }
  }

val editActionUnknown: VirtualKeyboardActionCallable =
  { ic, et, specialKey, mods, event, editHistory, service ->
    throw Error("Unknown action called")
  }

enum class VirtualKeyboardAction(val action: VirtualKeyboardActionCallable) {
  Unknown(editActionUnknown),
  SelectAll(editActionSelectAll),
  Copy(editActionCopy),
  Cut(editActionCut),
  Paste(editActionPaste),
  Left(editActionLeft),
  Right(editActionRight),
  BackSpace(editActionBackSpace),
  Delete(editActionDelete),
  Undo(editActionUndo),
  Redo(editActionRedo),
  InsertNewline(editActionInsertNewline),
  SendControlEnter(editActionSendControlEnter),
  HandleReturn(editActionHandleReturn),
  Up(editActionUp),
  Down(editActionDown),
  Home(editActionHome),
  End(editActionEnd),
  PageUp(editActionPageUp),
  PageDown(editActionPageDown);

  val actionId: Int = ordinal

  companion object {
    fun fromActionId(actionId: Int): VirtualKeyboardAction? {
      return entries.find { it.actionId == actionId }
    }

    fun fromName(name: String): VirtualKeyboardAction? {
      return entries.find { it.name.equals(name, ignoreCase = true) }
    }
  }
}
