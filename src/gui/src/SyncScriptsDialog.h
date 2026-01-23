/*
 * Deskflow -- mouse and keyboard sharing utility
 * SPDX-FileCopyrightText: 2025 Symless Ltd.
 * SPDX-License-Identifier: GPL-2.0-only WITH LicenseRef-OpenSSL-Exception
 */

#pragma once

#include <QComboBox>
#include <QDialog>
#include <QList>
#include <QMap>
#include <QString>
#include <QTableWidget>

class ServerConfig;

class SyncScriptsDialog : public QDialog
{
  Q_OBJECT

public:
  explicit SyncScriptsDialog(QWidget *parent, ServerConfig &config, const QStringList &clients);

  QMap<QString, QMap<QString, QString>> getSyncMap() const;

private:
  void buildUI();

  ServerConfig &m_config;
  QStringList m_clients;
  QTableWidget *m_table;
};
