package xebia.r2d2

import org.joda.time.DateTime

trait CurrentTime {
  def now: DateTime
}
