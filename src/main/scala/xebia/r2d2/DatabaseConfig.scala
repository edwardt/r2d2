package xebia.r2d2

import java.util.concurrent.TimeUnit

import com.typesafe.config.Config

import scala.concurrent.duration.Duration

trait DatabaseConfig {

  def connectionString: String
  def username: String
  def password: String
  def poolSize: Int
  def maxConnectionAge: Duration
  def maxConnectionIdleTime: Duration
  def connectionRequestTimeout: Duration
  def maxPendingConnections: Int
}
