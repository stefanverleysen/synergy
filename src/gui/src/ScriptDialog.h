/*
 * Deskflow -- mouse and keyboard sharing utility
 * SPDX-FileCopyrightText: 2025 Symless Ltd.
 * SPDX-License-Identifier: GPL-2.0-only WITH LicenseRef-OpenSSL-Exception
 */

#pragma once

#include <QDialog>
#include <QLineEdit>
#include <QPlainTextEdit>
#include <QTabWidget>

class ScriptDialog : public QDialog
{
  Q_OBJECT

public:
  explicit ScriptDialog(QWidget *parent = nullptr);

  void setScriptName(const QString &name);
  void setWindowsContent(const QString &content);
  void setMacContent(const QString &content);
  void setLinuxContent(const QString &content);

  QString scriptName() const;
  QString windowsContent() const;
  QString macContent() const;
  QString linuxContent() const;

private:
  QLineEdit *m_nameEdit;
  QTabWidget *m_tabWidget;
  QPlainTextEdit *m_windowsEdit;
  QPlainTextEdit *m_macEdit;
  QPlainTextEdit *m_linuxEdit;
};
