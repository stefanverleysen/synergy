/*
 * Deskflow -- mouse and keyboard sharing utility
 * Copyright (C) 2012 Symless Ltd.
 * Copyright (C) 2008 Volker Lanz (vl@fidra.de)
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

#include "ScreenSettingsDialog.h"

#include "gui/config/Screen.h"
#include "gui/styles.h"
#include "gui/validators/AliasValidator.h"
#include "gui/validators/ScreenNameValidator.h"
#include "gui/validators/ValidationError.h"

#include <QKeyEvent>
#include <QMessageBox>
#include <QtCore>
#include <QtGui>

using namespace deskflow::gui;
using enum ScreenConfig::Modifier;
using enum ScreenConfig::SwitchCorner;
using enum ScreenConfig::Fix;

ScreenSettingsDialog::ScreenSettingsDialog(QWidget *parent, Screen *pScreen, const ScreenList *pScreens)
    : QDialog(parent, Qt::WindowTitleHint | Qt::WindowSystemMenuHint),
      Ui::ScreenSettingsDialogBase(),
      m_pScreen(pScreen)
{

  setupUi(this);

  m_pLabelAliasError->setStyleSheet(kStyleErrorActiveLabel);
  m_pLabelNameError->setStyleSheet(kStyleErrorActiveLabel);

  m_pLineEditName->setText(m_pScreen->name());
  m_pLineEditName->setValidator(new validators::ScreenNameValidator(
      m_pLineEditName, new validators::ValidationError(this, m_pLabelNameError), pScreens
  ));
  m_pLineEditName->selectAll();

  m_pLineEditAlias->setValidator(
      new validators::AliasValidator(m_pLineEditAlias, new validators::ValidationError(this, m_pLabelAliasError))
  );

  for (int i = 0; i < m_pScreen->aliases().count(); i++)
    new QListWidgetItem(m_pScreen->aliases()[i], m_pListAliases);

  m_pComboBoxShift->setCurrentIndex(m_pScreen->modifier(static_cast<int>(Shift)));
  m_pComboBoxCtrl->setCurrentIndex(m_pScreen->modifier(static_cast<int>(Ctrl)));
  m_pComboBoxAlt->setCurrentIndex(m_pScreen->modifier(static_cast<int>(Alt)));
  m_pComboBoxMeta->setCurrentIndex(m_pScreen->modifier(static_cast<int>(Meta)));
  m_pComboBoxSuper->setCurrentIndex(m_pScreen->modifier(static_cast<int>(Super)));

  m_pCheckBoxCornerTopLeft->setChecked(m_pScreen->switchCorner(static_cast<int>(TopLeft)));
  m_pCheckBoxCornerTopRight->setChecked(m_pScreen->switchCorner(static_cast<int>(TopRight)));
  m_pCheckBoxCornerBottomLeft->setChecked(m_pScreen->switchCorner(static_cast<int>(BottomLeft)));
  m_pCheckBoxCornerBottomRight->setChecked(m_pScreen->switchCorner(static_cast<int>(BottomRight)));
  m_pSpinBoxSwitchCornerSize->setValue(m_pScreen->switchCornerSize());

  m_pCheckBoxCapsLock->setChecked(m_pScreen->fix(CapsLock));
  m_pCheckBoxNumLock->setChecked(m_pScreen->fix(NumLock));
  m_pCheckBoxScrollLock->setChecked(m_pScreen->fix(ScrollLock));
  m_pCheckBoxXTest->setChecked(m_pScreen->fix(XTest));

  m_pLineEditAnchoredKeys->setText(m_pScreen->anchoredKeys());
}

void ScreenSettingsDialog::accept()
{
  if (m_pLineEditName->text().isEmpty()) {
    QMessageBox::warning(
        this, tr("Screen name is empty"),
        tr("The screen name cannot be empty. "
           "Please either fill in a name or cancel the dialog.")
    );
    return;
  } else if (!m_pLabelNameError->text().isEmpty()) {
    return;
  }

  m_pScreen->init();

  m_pScreen->setName(m_pLineEditName->text());

  for (int i = 0; i < m_pListAliases->count(); i++) {
    QString alias(m_pListAliases->item(i)->text());
    if (alias == m_pLineEditName->text()) {
      QMessageBox::warning(
          this, tr("Screen name matches alias"),
          tr("The screen name cannot be the same as an alias. "
             "Please either remove the alias or change the screen name.")
      );
      return;
    }
    m_pScreen->addAlias(alias);
  }

  m_pScreen->setModifier(static_cast<int>(Shift), m_pComboBoxShift->currentIndex());
  m_pScreen->setModifier(static_cast<int>(Ctrl), m_pComboBoxCtrl->currentIndex());
  m_pScreen->setModifier(static_cast<int>(Alt), m_pComboBoxAlt->currentIndex());
  m_pScreen->setModifier(static_cast<int>(Meta), m_pComboBoxMeta->currentIndex());
  m_pScreen->setModifier(static_cast<int>(Super), m_pComboBoxSuper->currentIndex());

  m_pScreen->setSwitchCorner(static_cast<int>(TopLeft), m_pCheckBoxCornerTopLeft->isChecked());
  m_pScreen->setSwitchCorner(static_cast<int>(TopRight), m_pCheckBoxCornerTopRight->isChecked());
  m_pScreen->setSwitchCorner(static_cast<int>(BottomLeft), m_pCheckBoxCornerBottomLeft->isChecked());
  m_pScreen->setSwitchCorner(static_cast<int>(BottomRight), m_pCheckBoxCornerBottomRight->isChecked());
  m_pScreen->setSwitchCornerSize(m_pSpinBoxSwitchCornerSize->value());

  m_pScreen->setFix(static_cast<int>(CapsLock), m_pCheckBoxCapsLock->isChecked());
  m_pScreen->setFix(static_cast<int>(NumLock), m_pCheckBoxNumLock->isChecked());
  m_pScreen->setFix(static_cast<int>(ScrollLock), m_pCheckBoxScrollLock->isChecked());
  m_pScreen->setFix(static_cast<int>(XTest), m_pCheckBoxXTest->isChecked());

  m_pScreen->setAnchoredKeys(m_pLineEditAnchoredKeys->text());

  QDialog::accept();
}

void ScreenSettingsDialog::on_m_pButtonAddAlias_clicked()
{
  if (!m_pLineEditAlias->text().isEmpty() &&
      m_pListAliases->findItems(m_pLineEditAlias->text(), Qt::MatchFixedString).isEmpty()) {
    new QListWidgetItem(m_pLineEditAlias->text(), m_pListAliases);
    m_pLineEditAlias->clear();
  }
}

void ScreenSettingsDialog::on_m_pLineEditAlias_textChanged(const QString &text)
{
  m_pButtonAddAlias->setEnabled(!text.isEmpty() && m_pLabelAliasError->text().isEmpty());
}

void ScreenSettingsDialog::on_m_pButtonRemoveAlias_clicked()
{
  QList<QListWidgetItem *> items = m_pListAliases->selectedItems();

  for (int i = 0; i < items.count(); i++)
    delete items[i];
}

void ScreenSettingsDialog::on_m_pListAliases_itemSelectionChanged()
{
  m_pButtonRemoveAlias->setEnabled(!m_pListAliases->selectedItems().isEmpty());
}

void ScreenSettingsDialog::on_m_pButtonCaptureKey_clicked()
{
  m_capturingKey = true;
  m_pButtonCaptureKey->setText(tr("Press a key..."));
  qApp->installEventFilter(this);
}

void ScreenSettingsDialog::stopCapturing()
{
  m_capturingKey = false;
  m_pButtonCaptureKey->setText(tr("Capture"));
  qApp->removeEventFilter(this);
}

bool ScreenSettingsDialog::eventFilter(QObject *obj, QEvent *event)
{
  if (!m_capturingKey)
    return QDialog::eventFilter(obj, event);

  if (event->type() == QEvent::ShortcutOverride) {
    event->accept();
    return true;
  }

  if (event->type() == QEvent::KeyPress) {
    auto *keyEvent = static_cast<QKeyEvent *>(event);
    quint32 vk = keyEvent->nativeVirtualKey();

    // Qt returns 0 for some modifier-only presses
    if (vk == 0)
      vk = qtKeyToVk(keyEvent->key());

    QString name = vkCodeToName(vk);

    if (!name.isEmpty()) {
      QString current = m_pLineEditAnchoredKeys->text().trimmed();
      if (!current.isEmpty())
        current += ", ";
      current += name;
      m_pLineEditAnchoredKeys->setText(current);
    }

    stopCapturing();
    return true;
  }

  return QDialog::eventFilter(obj, event);
}

QString ScreenSettingsDialog::vkCodeToName(quint32 vk)
{
  struct VkEntry {
    quint32 vk;
    const char *name;
  };

  static const VkEntry entries[] = {
      {0x08, "Backspace"}, {0x09, "Tab"},       {0x0D, "Enter"},
      {0x10, "Shift"},     {0x11, "Control"},   {0x12, "Alt"},
      {0x13, "Pause"},     {0x14, "CapsLock"},  {0x1B, "Escape"},
      {0x20, "Space"},     {0x21, "PageUp"},    {0x22, "PageDown"},
      {0x23, "End"},       {0x24, "Home"},      {0x25, "Left"},
      {0x26, "Up"},        {0x27, "Right"},     {0x28, "Down"},
      {0x2C, "PrintScreen"}, {0x2D, "Insert"},  {0x2E, "Delete"},
      {0x5B, "LeftWin"},   {0x5C, "RightWin"},  {0x5D, "Apps"},
      {0x60, "NumPad0"},   {0x61, "NumPad1"},   {0x62, "NumPad2"},
      {0x63, "NumPad3"},   {0x64, "NumPad4"},   {0x65, "NumPad5"},
      {0x66, "NumPad6"},   {0x67, "NumPad7"},   {0x68, "NumPad8"},
      {0x69, "NumPad9"},
      {0x6A, "NumPadMultiply"}, {0x6B, "NumPadAdd"},
      {0x6D, "NumPadSubtract"}, {0x6E, "NumPadDecimal"},
      {0x6F, "NumPadDivide"},
      {0x90, "NumLock"},   {0x91, "ScrollLock"},
      {0xA0, "LeftShift"}, {0xA1, "RightShift"},
      {0xA2, "LeftCtrl"},  {0xA3, "RightCtrl"},
      {0xA4, "LeftAlt"},   {0xA5, "RightAlt"},
      {0xA6, "BrowserBack"},    {0xA7, "BrowserForward"},
      {0xAD, "VolumeMute"}, {0xAE, "VolumeDown"}, {0xAF, "VolumeUp"},
      {0xB0, "MediaNext"}, {0xB1, "MediaPrev"},
      {0xB2, "MediaStop"}, {0xB3, "MediaPlay"},
  };

  // F1-F24
  if (vk >= 0x70 && vk <= 0x87)
    return QString("F%1").arg(vk - 0x70 + 1);

  // A-Z
  if (vk >= 0x41 && vk <= 0x5A)
    return QString(QChar(vk));

  // 0-9
  if (vk >= 0x30 && vk <= 0x39)
    return QString(QChar(vk));

  for (const auto &entry : entries) {
    if (entry.vk == vk)
      return QString(entry.name);
  }

  return QString();
}

quint32 ScreenSettingsDialog::qtKeyToVk(int qtKey)
{
  switch (qtKey) {
  case Qt::Key_Shift:     return 0x10;
  case Qt::Key_Control:   return 0x11;
  case Qt::Key_Alt:       return 0x12;
  case Qt::Key_Meta:      return 0x5B;
  case Qt::Key_CapsLock:  return 0x14;
  case Qt::Key_NumLock:   return 0x90;
  case Qt::Key_ScrollLock: return 0x91;
  case Qt::Key_Pause:     return 0x13;
  case Qt::Key_Print:     return 0x2C;
  case Qt::Key_Escape:    return 0x1B;
  default:                return 0;
  }
}
