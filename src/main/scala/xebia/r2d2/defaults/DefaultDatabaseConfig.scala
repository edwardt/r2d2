package xebia.r2d2.defaults

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory
import xebia.r2d2.DatabaseConfig

import scala.concurrent.duration.Duration

trait DefaultDatabaseConfig extends DatabaseConfig {

  val config = ConfigFactory.load()
  val basePath = "database.default"

  override val connectionString = config.getString(s"$basePath.connection-string")
  override val username = config.getString(s"$basePath.username")
  override val password = config.getString(s"$basePath.password")
  override val poolSize = config.getInt(s"$basePath.pool-size")
  override val maxConnectionAge = Duration(config.getDuration(s"$basePath.max-connection-age", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  override val maxConnectionIdleTime = Duration(config.getDuration(s"$basePath.max-connection-idle-time", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  override val connectionRequestTimeout = Duration(config.getDuration(s"$basePath.max-connection-acquisition-time", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
  override val maxPendingConnections = config.getInt(s"$basePath.max-pending-connections")
}
