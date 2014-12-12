package com.plotprojects.dao.postgresql

import com.typesafe.config.Config

case class DatabaseConfig(host: String,
                          databaseName: String,
                          username: String,
                          password: String,
                          poolSize: Int,
                          maxConnectionAge: Long,
                          maxConnectionIdleTime: Long,
                          maxConnectionCheckoutTime: Long,
                          maxPendingConnections: Int)

object DatabaseConfig {
  def getConfig(config: Config) = {
    DatabaseConfig(
      host = config.getString("plot.db.postgresql.host"),
      databaseName = config.getString("plot.db.postgresql.database"),
      username = config.getString("plot.db.postgresql.username"),
      password = config.getString("plot.db.postgresql.password"),
      poolSize = config.getInt("plot.db.postgresql.poolSize"),
      maxConnectionAge = config.getMilliseconds("plot.db.postgresql.maxConnectionAge"),
      maxConnectionIdleTime = config.getMilliseconds("plot.db.postgresql.maxConnectionIdleTime"),
      maxConnectionCheckoutTime = config.getMilliseconds("plot.db.postgresql.maxConnectionCheckoutTime"),
      maxPendingConnections = config.getInt("plot.db.postgresql.maxPendingConnections"))
  }
}
