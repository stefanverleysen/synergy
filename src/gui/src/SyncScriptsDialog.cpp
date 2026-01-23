/*
 * Deskflow -- mouse and keyboard sharing utility
 * SPDX-FileCopyrightText: 2025 Symless Ltd.
 * SPDX-License-Identifier: GPL-2.0-only WITH LicenseRef-OpenSSL-Exception
 */

#include "SyncScriptsDialog.h"
#include "ServerConfig.h"

#include <QDialogButtonBox>
#include <QHeaderView>
#include <QLabel>
#include <QPushButton>
#include <QVBoxLayout>

SyncScriptsDialog::SyncScriptsDialog(QWidget *parent, ServerConfig &config, const QStringList &clients)
    : QDialog(parent),
      m_config(config),
      m_clients(clients)
{
  setWindowTitle(tr("Sync Scripts to Clients"));
  setMinimumSize(500, 400);
  buildUI();
}

void SyncScriptsDialog::buildUI()
{
  auto *layout = new QVBoxLayout(this);

  if (m_clients.isEmpty()) {
    auto *label = new QLabel(tr("No clients connected. Connect clients first, then sync scripts."), this);
    label->setWordWrap(true);
    layout->addWidget(label);

    auto *buttonBox = new QDialogButtonBox(QDialogButtonBox::Close, this);
    connect(buttonBox, &QDialogButtonBox::rejected, this, &QDialog::reject);
    layout->addWidget(buttonBox);
    return;
  }

  if (m_config.scripts().isEmpty()) {
    auto *label = new QLabel(tr("No scripts defined. Create scripts first, then sync them."), this);
    label->setWordWrap(true);
    layout->addWidget(label);

    auto *buttonBox = new QDialogButtonBox(QDialogButtonBox::Close, this);
    connect(buttonBox, &QDialogButtonBox::rejected, this, &QDialog::reject);
    layout->addWidget(buttonBox);
    return;
  }

  auto *label = new QLabel(tr("Select which script version to send to each client:"), this);
  layout->addWidget(label);

  m_table = new QTableWidget(this);
  m_table->setColumnCount(m_clients.size() + 1);

  QStringList headers;
  headers << tr("Script");
  headers << m_clients;
  m_table->setHorizontalHeaderLabels(headers);
  m_table->horizontalHeader()->setStretchLastSection(true);
  m_table->verticalHeader()->setVisible(false);

  m_table->setRowCount(m_config.scripts().size());

  for (int row = 0; row < m_config.scripts().size(); ++row) {
    const Script &script = m_config.scripts().at(row);

    auto *nameItem = new QTableWidgetItem(script.name);
    nameItem->setFlags(nameItem->flags() & ~Qt::ItemIsEditable);
    m_table->setItem(row, 0, nameItem);

    for (int col = 0; col < m_clients.size(); ++col) {
      auto *combo = new QComboBox(this);
      combo->addItem(tr("(skip)"), "");
      if (!script.windowsContent.isEmpty())
        combo->addItem(tr("Windows"), "windows");
      if (!script.macContent.isEmpty())
        combo->addItem(tr("macOS"), "mac");
      if (!script.linuxContent.isEmpty())
        combo->addItem(tr("Linux"), "linux");

      m_table->setCellWidget(row, col + 1, combo);
    }
  }

  m_table->resizeColumnsToContents();
  layout->addWidget(m_table);

  auto *buttonBox = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, this);
  buttonBox->button(QDialogButtonBox::Ok)->setText(tr("Sync Now"));
  connect(buttonBox, &QDialogButtonBox::accepted, this, &QDialog::accept);
  connect(buttonBox, &QDialogButtonBox::rejected, this, &QDialog::reject);
  layout->addWidget(buttonBox);
}

QMap<QString, QMap<QString, QString>> SyncScriptsDialog::getSyncMap() const
{
  QMap<QString, QMap<QString, QString>> result;

  if (!m_table)
    return result;

  for (int row = 0; row < m_config.scripts().size(); ++row) {
    const Script &script = m_config.scripts().at(row);

    for (int col = 0; col < m_clients.size(); ++col) {
      auto *combo = qobject_cast<QComboBox *>(m_table->cellWidget(row, col + 1));
      if (!combo)
        continue;

      QString platform = combo->currentData().toString();
      if (platform.isEmpty())
        continue;

      QString client = m_clients.at(col);
      QString content;
      if (platform == "windows")
        content = script.windowsContent;
      else if (platform == "mac")
        content = script.macContent;
      else if (platform == "linux")
        content = script.linuxContent;

      if (!content.isEmpty()) {
        result[client][script.name] = content;
      }
    }
  }

  return result;
}
