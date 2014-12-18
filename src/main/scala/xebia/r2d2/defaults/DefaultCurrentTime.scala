package xebia.r2d2.defaults

import org.joda.time.{ DateTime, DateTimeZone }
import xebia.r2d2.CurrentTime

trait DefaultCurrentTime extends CurrentTime {
  override def now: DateTime = DateTime.now(DateTimeZone.UTC)
}
