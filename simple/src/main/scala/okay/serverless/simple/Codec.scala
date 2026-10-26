package okay.serverless.simple

/** how a value is kept: as a text — JSON, the stored format okay-scala's `okay.serverless.Codec` writes too */
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
    of(a => to(a).render, s => Json.parse(s).flatMap(from))

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
    as => Json.Arr(as.map(a => Json.parse(c.encode(a)).getOrElse(Json.Str(c.encode(a))))).render,
    s => Json.parse(s).flatMap {
      case Json.Arr(xs) => xs.foldRight[Either[String, List[A]]](Right(Nil))((x, acc) =>
        for { a <- c.decode(x.render); rest <- acc } yield a :: rest)
      case j => Left(s"not a list: $j")
    })
}

/** JSON: the stored format */
sealed trait Json {
  def render: String = this match {
    case Json.Null => "null"
    case Json.Bool(b) => b.toString
    case Json.Num(n) => n.bigDecimal.toPlainString
    case Json.Str(s) => Json.quote(s)
    case Json.Arr(xs) => xs.map(_.render).mkString("[", ",", "]")
    case Json.Obj(fs) => fs.map { case (k, v) => Json.quote(k) + ":" + v.render }.mkString("{", ",", "}")
  }

  def get(key: String): Option[Json] = this match {
    case Json.Obj(fs) => fs.collectFirst { case (k, v) if k == key => v }
    case _ => None
  }
}

object Json {
  case object Null extends Json
  final case class Bool(b: Boolean) extends Json
  final case class Num(n: BigDecimal) extends Json
  final case class Str(s: String) extends Json
  final case class Arr(xs: List[Json]) extends Json
  final case class Obj(fields: List[(String, Json)]) extends Json

  def quote(s: String): String = {
    val b = new StringBuilder("\"")
    s.foreach {
      case '"' => b ++= "\\\""
      case '\\' => b ++= "\\\\"
      case '\n' => b ++= "\\n"
      case '\r' => b ++= "\\r"
      case '\t' => b ++= "\\t"
      case c if c < ' ' => b ++= f"\\u${c.toInt}%04x"
      case c => b += c
    }
    b += '"'
    b.toString
  }

  /** a text's JSON, or what is wrong with it */
  def parse(s: String): Either[String, Json] = {
    var i = 0
    def ws(): Unit = while (i < s.length && s(i).isWhitespace) i += 1
    def fail(what: String) = throw new IllegalArgumentException(s"$what at $i")
    def many[A](close: Char)(one: () => A): List[A] = {
      val xs = scala.collection.mutable.ListBuffer.empty[A]
      ws()
      if (s(i) == close) i += 1
      else {
        var more = true
        while (more) {
          ws(); xs += one(); ws()
          if (s(i) == ',') i += 1
          else if (s(i) == close) { i += 1; more = false }
          else fail(s"',' or '$close'")
        }
      }
      xs.toList
    }
    def value(): Json = {
      ws()
      if (i >= s.length) fail("a value")
      s(i) match {
        case '{' =>
          i += 1
          Obj(many('}') { () =>
            val k = string(); ws()
            if (s(i) != ':') fail("':'")
            i += 1
            k -> value()
          })
        case '[' => i += 1; Arr(many(']')(() => value()))
        case '"' => Str(string())
        case 't' if s.startsWith("true", i) => i += 4; Bool(true)
        case 'f' if s.startsWith("false", i) => i += 5; Bool(false)
        case 'n' if s.startsWith("null", i) => i += 4; Null
        case _ =>
          val start = i
          while (i < s.length && "+-0123456789.eE".contains(s(i))) i += 1
          if (start == i) fail("a value")
          Num(BigDecimal(s.substring(start, i)))
      }
    }
    def string(): String = {
      if (s(i) != '"') fail("a text")
      i += 1
      val b = new StringBuilder
      while (s(i) != '"') {
        if (s(i) == '\\') {
          i += 1
          s(i) match {
            case 'n' => b += '\n'
            case 't' => b += '\t'
            case 'r' => b += '\r'
            case 'b' => b += '\b'
            case 'f' => b += '\f'
            case 'u' => b += Integer.parseInt(s.substring(i + 1, i + 5), 16).toChar; i += 4
            case c => b += c
          }
        } else b += s(i)
        i += 1
      }
      i += 1
      b.toString
    }
    try {
      val v = value(); ws()
      if (i != s.length) Left(s"more after the value at $i") else Right(v)
    } catch { case e: Exception => Left(e.getMessage) }
  }
}
