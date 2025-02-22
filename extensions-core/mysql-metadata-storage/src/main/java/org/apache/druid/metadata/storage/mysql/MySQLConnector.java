/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.metadata.storage.mysql;

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.metadata.MetadataStorageConnectorConfig;
import org.apache.druid.metadata.MetadataStorageTablesConfig;
import org.apache.druid.metadata.SQLMetadataConnector;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.util.StringMapper;

import javax.annotation.Nullable;
import java.io.File;
import java.sql.SQLException;
import java.util.Locale;

public class MySQLConnector extends SQLMetadataConnector
{
  private static final Logger log = new Logger(MySQLConnector.class);
  private static final String PAYLOAD_TYPE = "LONGBLOB";
  private static final String SERIAL_TYPE = "BIGINT(20) AUTO_INCREMENT";
  private static final String QUOTE_STRING = "`";
  private static final String COLLATION = "CHARACTER SET utf8mb4 COLLATE utf8mb4_bin";
  private static final String MYSQL_TRANSIENT_EXCEPTION_CLASS_NAME = "com.mysql.jdbc.exceptions.MySQLTransientException";

  @Nullable
  private final Class<?> myTransientExceptionClass;
  private final DBI dbi;

  @Inject
  public MySQLConnector(
      Supplier<MetadataStorageConnectorConfig> config,
      Supplier<MetadataStorageTablesConfig> dbTables,
      MySQLConnectorSslConfig connectorSslConfig,
      MySQLConnectorDriverConfig driverConfig
  )
  {
    super(config, dbTables);
    this.dbi = createDBI(config.get(), driverConfig, connectorSslConfig, getValidationQuery());

    if (driverConfig.getDriverClassName().contains("mysql")) {
      myTransientExceptionClass = tryLoadDriverClass(MYSQL_TRANSIENT_EXCEPTION_CLASS_NAME, false);
    } else {
      myTransientExceptionClass = null;
    }
  }

  public static DBI createDBI(MetadataStorageConnectorConfig config, MySQLConnectorDriverConfig driverConfig, MySQLConnectorSslConfig connectorSslConfig, String validationQuery)
  {
    log.info("Loading \"MySQL\" metadata connector driver %s", driverConfig.getDriverClassName());
    tryLoadDriverClass(driverConfig.getDriverClassName(), true);

    final BasicDataSource datasource = makeDatasource(config, validationQuery);
    // MySQL driver is classloader isolated as part of the extension
    // so we need to help JDBC find the driver
    datasource.setDriverClassLoader(MySQLConnector.class.getClassLoader());
    datasource.setDriverClassName(driverConfig.getDriverClassName());
    datasource.addConnectionProperty("useSSL", String.valueOf(connectorSslConfig.isUseSSL()));
    if (connectorSslConfig.isUseSSL()) {
      log.info("SSL is enabled on this MySQL connection. ");

      datasource.addConnectionProperty(
          "verifyServerCertificate",
          String.valueOf(connectorSslConfig.isVerifyServerCertificate())
      );
      if (connectorSslConfig.isVerifyServerCertificate()) {
        log.info("Server certificate verification is enabled. ");

        if (connectorSslConfig.getTrustCertificateKeyStoreUrl() != null) {
          datasource.addConnectionProperty(
              "trustCertificateKeyStoreUrl",
              new File(connectorSslConfig.getTrustCertificateKeyStoreUrl()).toURI().toString()
          );
        }
        if (connectorSslConfig.getTrustCertificateKeyStoreType() != null) {
          datasource.addConnectionProperty(
              "trustCertificateKeyStoreType",
              connectorSslConfig.getTrustCertificateKeyStoreType()
          );
        }
        if (connectorSslConfig.getTrustCertificateKeyStorePassword() == null) {
          log.warn(
              "Trust store password is empty. Ensure that the trust store has been configured with an empty password.");
        } else {
          datasource.addConnectionProperty(
              "trustCertificateKeyStorePassword",
              connectorSslConfig.getTrustCertificateKeyStorePassword()
          );
        }
      }
      if (connectorSslConfig.getClientCertificateKeyStoreUrl() != null) {
        datasource.addConnectionProperty(
            "clientCertificateKeyStoreUrl",
            new File(connectorSslConfig.getClientCertificateKeyStoreUrl()).toURI().toString()
        );
      }
      if (connectorSslConfig.getClientCertificateKeyStoreType() != null) {
        datasource.addConnectionProperty(
            "clientCertificateKeyStoreType",
            connectorSslConfig.getClientCertificateKeyStoreType()
        );
      }
      if (connectorSslConfig.getClientCertificateKeyStorePassword() != null) {
        datasource.addConnectionProperty(
            "clientCertificateKeyStorePassword",
            connectorSslConfig.getClientCertificateKeyStorePassword()
        );
      }
      Joiner joiner = Joiner.on(",").skipNulls();
      if (connectorSslConfig.getEnabledSSLCipherSuites() != null) {
        datasource.addConnectionProperty(
            "enabledSSLCipherSuites",
            joiner.join(connectorSslConfig.getEnabledSSLCipherSuites())
        );
      }
      if (connectorSslConfig.getEnabledTLSProtocols() != null) {
        datasource.addConnectionProperty("enabledTLSProtocols", joiner.join(connectorSslConfig.getEnabledTLSProtocols()));
      }
    }

    // use double-quotes for quoting columns, so we can write SQL that works with most databases
    datasource.setConnectionInitSqls(ImmutableList.of("SET sql_mode='ANSI_QUOTES'"));

    DBI dbi = new DBI(datasource);

    log.info("Configured MySQL as metadata storage");
    return dbi;
  }

  @Override
  public String getPayloadType()
  {
    return PAYLOAD_TYPE;
  }

  @Override
  public String getSerialType()
  {
    return SERIAL_TYPE;
  }

  @Override
  public String getCollation()
  {
    return COLLATION;
  }

  @Override
  public String getQuoteString()
  {
    return QUOTE_STRING;
  }

  @Override
  public int getStreamingFetchSize()
  {
    // this is MySQL's way of indicating you want results streamed back
    // see http://dev.mysql.com/doc/connector-j/5.1/en/connector-j-reference-implementation-notes.html
    return Integer.MIN_VALUE;
  }

  @Override
  public String limitClause(int limit)
  {
    return String.format(Locale.ENGLISH, "LIMIT %d", limit);
  }

  @Override
  public boolean tableExists(Handle handle, String tableName)
  {
    String databaseCharset = handle
        .createQuery("SELECT @@character_set_database")
        .map(StringMapper.FIRST)
        .first();

    if (!databaseCharset.startsWith("utf8")) {
      throw new ISE(
          "Druid requires its MySQL database to be created with an UTF8 charset, found `%1$s`. "
          + "The recommended charset is `utf8mb4`.",
          databaseCharset
      );
    } else if (!"utf8mb4".equals(databaseCharset)) {
      log.warn("The current database charset `%1$s` does not match the recommended charset `utf8mb4`", databaseCharset);
    }

    return !handle.createQuery("SHOW tables LIKE :tableName")
                  .bind("tableName", tableName)
                  .list()
                  .isEmpty();
  }

  @Override
  protected boolean connectorIsTransientException(Throwable e)
  {
    if (myTransientExceptionClass != null) {
      return myTransientExceptionClass.isAssignableFrom(e.getClass())
             || e instanceof SQLException && ((SQLException) e).getErrorCode() == 1317 /* ER_QUERY_INTERRUPTED */;
    }
    return false;
  }

  @Override
  public Void insertOrUpdate(
      final String tableName,
      final String keyColumn,
      final String valueColumn,
      final String key,
      final byte[] value
  )
  {
    return getDBI().withHandle(
        handle -> {
          handle.createStatement(
              StringUtils.format(
                  "INSERT INTO %1$s (%2$s, %3$s) VALUES (:key, :value) ON DUPLICATE KEY UPDATE %3$s = :value",
                  tableName,
                  keyColumn,
                  valueColumn
              )
          )
                .bind("key", key)
                .bind("value", value)
                .execute();
          return null;
        }
    );
  }

  @Override
  public DBI getDBI()
  {
    return dbi;
  }

  @Nullable
  private static Class<?> tryLoadDriverClass(String className, boolean failIfNotFound)
  {
    try {
      return Class.forName(className, false, MySQLConnector.class.getClassLoader());
    }
    catch (ClassNotFoundException e) {
      if (failIfNotFound) {
        throw new ISE(e, "Could not find %s on the classpath. The MySQL Connector library is not included in the Druid "
                         + "distribution but is required to use MySQL. Please download a compatible library (for example "
                         + "'mysql-connector-java-5.1.48.jar') and place it under 'extensions/mysql-metadata-storage/'. See "
                         + "https://druid.apache.org/downloads for more details.",
                      className
        );
      }
      log.warn("Could not find %s on the classpath.", className);
      return null;
    }
  }
}
