package okay.serverless.simple

import okay.effects.json.{Descent, Json}

/** how a value is kept: as a text — JSON (okay-simple's `okay.effects.json`), the stored format okay-scala's
 * `okay.serverless.Codec` writes too */
trait Codec[A] {
  def encode(a: A): String
  def decode(s: String): Either[String, A]
}

object Codec {
  def of[A](enc: A => String, dec: String => Either[String, A]): Codec[A] = new Codec[A] {
    def encode(a: A): String = enc(a)
    def decode(s: String): Either[String, A] = dec(s)
  }

  /** a value as JSON, by its JSON form */
  def json[A](to: A => Json, from: Json => Either[String, A]): Codec[A] =
    of(a => Json.print(to(a)), s => Descent.parse(s).left.map(_.message).flatMap(from))

  implicit val string: Codec[String] =
    json(Json.Str(_), { case Json.Str(s) => Right(s); case j => Left(s"not a text: $j") })
  implicit val long: Codec[Long] = json(n => Json.Num(BigDecimal(n)), {
    case Json.Num(n) if n.isValidLong => Right(n.toLong)
    case j => Left(s"not a whole number: $j")
  })
  implicit val int: Codec[Int] = json(n => Json.Num(BigDecimal(n)), {
    case Json.Num(n) if n.isValidInt => Right(n.toInt)
    case j => Left(s"not an Int: $j")
  })
  implicit val bigDecimal: Codec[BigDecimal] =
    json(Json.Num(_), { case Json.Num(n) => Right(n); case j => Left(s"not a number: $j") })
  implicit val boolean: Codec[Boolean] =
    json(Json.Bool(_), { case Json.Bool(b) => Right(b); case j => Left(s"not true or false: $j") })
  implicit def list[A](implicit c: Codec[A]): Codec[List[A]] = of(
    as => Json.print(Json.Arr(as.toVector.map(a => Descent.parse(c.encode(a)).getOrElse(Json.Str(c.encode(a)))))),
    s => Descent.parse(s).left.map(_.message).flatMap {
      case Json.Arr(xs) => xs.foldRight[Either[String, List[A]]](Right(Nil))((x, acc) =>
        for { a <- c.decode(Json.print(x)); rest <- acc } yield a :: rest)
      case j => Left(s"not a list: $j")
    })
}
