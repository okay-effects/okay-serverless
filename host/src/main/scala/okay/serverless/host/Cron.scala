package okay.serverless.host

import java.time.{Instant, ZoneOffset}

/**
 * A CRON TRIGGER (five fields, UTC, as Cloudflare's): minute, hour, day of the month, month, day of the week — each
 * `*`, a number, a range `a-b`, a list `a,b`, a step `*\/n` or `a-b/n`; the day of the week 0-7 (0 and 7 Sunday).
 * When both days are restricted, either matches (cron's rule).
 */
final case class Cron(text: String, minutes: Set[Int], hours: Set[Int], days: Set[Int], months: Set[Int],
                      weekdays: Set[Int], anyDay: Boolean, anyWeekday: Boolean):
  def matches(millis: Long): Boolean =
    val t = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
    val day = days(t.getDayOfMonth)
    val weekday = weekdays(t.getDayOfWeek.getValue % 7)
    minutes(t.getMinute) && hours(t.getHour) && months(t.getMonthValue) &&
      (if anyDay || anyWeekday then day && weekday else day || weekday)

object Cron:
  def parse(text: String): Either[String, Cron] =
    text.trim.split("\\s+").toList match
      case List(mi, h, d, mo, w) =>
        for
          minutes <- field(mi, 0, 59)
          hours <- field(h, 0, 23)
          days <- field(d, 1, 31)
          months <- field(mo, 1, 12)
          weekdays <- field(w, 0, 7).map(_.map(_ % 7))
        yield Cron(text.trim, minutes, hours, days, months, weekdays, d == "*", w == "*")
      case _ => Left(s"a cron trigger has five fields: '$text'")

  private def field(f: String, lo: Int, hi: Int): Either[String, Set[Int]] =
    f.split(',').toList.foldLeft[Either[String, Set[Int]]](Right(Set.empty)) { (acc, part) =>
      acc.flatMap(s => one(part, lo, hi).map(s ++ _))
    }

  private def one(part: String, lo: Int, hi: Int): Either[String, Set[Int]] =
    val (range, step) = part.split('/') match
      case Array(r, n) => (r, n.toIntOption)
      case Array(r) => (r, Some(1))
      case _ => ("", None)
    step match
      case None | Some(0) => Left(s"a step in '$part'")
      case Some(n) if n < 0 => Left(s"a step in '$part'")
      case Some(n) =>
        val bounds = range match
          case "*" => Some((lo, hi))
          case r if r.contains('-') => r.split('-') match
            case Array(a, b) => a.toIntOption.zip(b.toIntOption)
            case _ => None
          case r => r.toIntOption.map(x => (x, if part.contains('/') then hi else x))
        bounds match
          case Some((a, b)) if a >= lo && b <= hi && a <= b => Right((a to b by n).toSet)
          case _ => Left(s"'$part' is not within $lo-$hi")
