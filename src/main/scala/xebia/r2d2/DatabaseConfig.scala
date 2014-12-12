package xebia.r2d2

import java.util.concurrent.TimeUnit

import com.typesafe.config.Config

case class DatabaseConfig(connectionString: String,
                          username: String,
                          password: String,
                          poolSize: Int,
                          maxConnectionAge: Long,
                          maxConnectionIdleTime: Long,
                          connectionRequestTimeout: Long,
                          maxPendingConnections: Int)

object DatabaseConfig {
  def getConfig(config: Config) = {
    DatabaseConfig(
      connectionString = config.getString("plot.db.connectionString"),
      username = config.getString("plot.db.postgresql.username"),
      password = config.getString("plot.db.postgresql.password"),
      poolSize = config.getInt("plot.db.postgresql.poolSize"),
      maxConnectionAge = config.getDuration("plot.db.postgresql.maxConnectionAge", TimeUnit.MILLISECONDS),
      maxConnectionIdleTime = config.getDuration("plot.db.postgresql.maxConnectionIdleTime", TimeUnit.MILLISECONDS),
      connectionRequestTimeout = config.getDuration("plot.db.postgresql.maxConnectionCheckoutTime", TimeUnit.MILLISECONDS),
      maxPendingConnections = config.getInt("plot.db.postgresql.maxPendingConnections"))
  }
}
