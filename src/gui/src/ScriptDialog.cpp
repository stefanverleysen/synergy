/*
 * Deskflow -- mouse and keyboard sharing utility
 * SPDX-FileCopyrightText: 2025 Symless Ltd.
 * SPDX-License-Identifier: GPL-2.0-only WITH LicenseRef-OpenSSL-Exception
 */

#include "ScriptDialog.h"

#include <QDialogButtonBox>
#include <QFormLayout>
#include <QLabel>
#include <QVBoxLayout>

ScriptDialog::ScriptDialog(QWidget *parent)
    : QDialog(parent)
{
  setWindowTitle(tr("Edit Script"));
  setMinimumSize(500, 400);

  auto *layout = new QVBoxLayout(this);

  auto *nameLayout = new QFormLayout();
  m_nameEdit = new QLineEdit(this);
  m_nameEdit->setPlaceholderText(tr("Script name (e.g., backup, screenshot)"));
  nameLayout->addRow(tr("Name:"), m_nameEdit);
  layout->addLayout(nameLayout);

  m_tabWidget = new QTabWidget(this);

  m_windowsEdit = new QPlainTextEdit(this);
  m_windowsEdit->setPlaceholderText(tr("PowerShell script for Windows"));
  m_tabWidget->addTab(m_windowsEdit, tr("Windows"));

  m_macEdit = new QPlainTextEdit(this);
  m_macEdit->setPlaceholderText(tr("Bash script for macOS"));
  m_tabWidget->addTab(m_macEdit, tr("macOS"));

  m_linuxEdit = new QPlainTextEdit(this);
  m_linuxEdit->setPlaceholderText(tr("Bash script for Linux"));
  m_tabWidget->addTab(m_linuxEdit, tr("Linux"));

  layout->addWidget(m_tabWidget);

  auto *buttonBox = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, this);
  connect(buttonBox, &QDialogButtonBox::accepted, this, &QDialog::accept);
  connect(buttonBox, &QDialogButtonBox::rejected, this, &QDialog::reject);
  layout->addWidget(buttonBox);
}

void ScriptDialog::setScriptName(const QString &name)
{
  m_nameEdit->setText(name);
}

void ScriptDialog::setWindowsContent(const QString &content)
{
  m_windowsEdit->setPlainText(content);
}

void ScriptDialog::setMacContent(const QString &content)
{
  m_macEdit->setPlainText(content);
}

void ScriptDialog::setLinuxContent(const QString &content)
{
  m_linuxEdit->setPlainText(content);
}

QString ScriptDialog::scriptName() const
{
  return m_nameEdit->text();
}

QString ScriptDialog::windowsContent() const
{
  return m_windowsEdit->toPlainText();
}

QString ScriptDialog::macContent() const
{
  return m_macEdit->toPlainText();
}

QString ScriptDialog::linuxContent() const
{
  return m_linuxEdit->toPlainText();
}
