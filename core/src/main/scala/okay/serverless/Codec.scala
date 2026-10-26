package okay.serverless

/** how a value is kept: as a text — JSON, the stored format every implementation reads (specs/durable.md) */
trait Codec[A]:
  def encode(a: A): String
  def decode(s: String): Either[String, A]

object Codec:
  def of[A](enc: A => String, dec: String => Either[String, A]): Codec[A] = new Codec[A]:
    def encode(a: A): String = enc(a)
    def decode(s: String): Either[String, A] = dec(s)

  /** a value as JSON, by its JSON form */
  def json[A](to: A => Json, from: Json => Either[String, A]): Codec[A] =
    of(a => to(a).render, s => Json.parse(s).flatMap(from))

  given Codec[String] = json(Json.Str(_), { case Json.Str(s) => Right(s); case j => Left(s"not a text: $j") })
  given Codec[Long] = json(n => Json.Num(BigDecimal(n)), {
    case Json.Num(n) if n.isValidLong => Right(n.toLong)
    case j => Left(s"not a whole number: $j")
  })
  given Codec[Int] = json(n => Json.Num(BigDecimal(n)), {
    case Json.Num(n) if n.isValidInt => Right(n.toInt)
    case j => Left(s"not an Int: $j")
  })
  given Codec[BigDecimal] = json(Json.Num(_), { case Json.Num(n) => Right(n); case j => Left(s"not a number: $j") })
  given Codec[Boolean] = json(Json.Bool(_), { case Json.Bool(b) => Right(b); case j => Left(s"not true or false: $j") })
  given [A](using c: Codec[A]): Codec[List[A]] = of(
    as => as.map(a => Json.parse(c.encode(a)).getOrElse(Json.Str(c.encode(a)))).pipe(Json.Arr(_)).render,
    s => Json.parse(s).flatMap {
      case Json.Arr(xs) => xs.foldRight[Either[String, List[A]]](Right(Nil))((x, acc) =>
        for a <- c.decode(x.render); rest <- acc yield a :: rest)
      case j => Left(s"not a list: $j")
    })

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

/** JSON: the stored format */
enum Json:
  case Null
  case Bool(b: Boolean)
  case Num(n: BigDecimal)
  case Str(s: String)
  case Arr(xs: List[Json])
  case Obj(fields: List[(String, Json)])

  def render: String = this match
    case Null => "null"
    case Bool(b) => b.toString
    case Num(n) => n.bigDecimal.toPlainString
    case Str(s) => Json.quote(s)
    case Arr(xs) => xs.map(_.render).mkString("[", ",", "]")
    case Obj(fs) => fs.map((k, v) => Json.quote(k) + ":" + v.render).mkString("{", ",", "}")

  def get(key: String): Option[Json] = this match
    case Obj(fs) => fs.collectFirst { case (`key`, v) => v }
    case _ => None

object Json:
  def quote(s: String): String =
    val b = StringBuilder("\"")
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

  /** a text's JSON, or what is wrong with it */
  def parse(s: String): Either[String, Json] =
    var i = 0
    def ws(): Unit = while i < s.length && s(i).isWhitespace do i += 1
    def fail(what: String) = throw IllegalArgumentException(s"$what at $i")
    def value(): Json =
      ws()
      if i >= s.length then fail("a value")
      s(i) match
        case '{' =>
          i += 1; ws()
          val fs = scala.collection.mutable.ListBuffer.empty[(String, Json)]
          if s(i) == '}' then i += 1
          else
            var more = true
            while more do
              ws(); val k = string(); ws()
              if s(i) != ':' then fail("':'")
              i += 1
              fs += k -> value(); ws()
              s(i) match
                case ',' => i += 1
                case '}' => i += 1; more = false
                case _ => fail("',' or '}'")
          Obj(fs.toList)
        case '[' =>
          i += 1; ws()
          val xs = scala.collection.mutable.ListBuffer.empty[Json]
          if s(i) == ']' then i += 1
          else
            var more = true
            while more do
              xs += value(); ws()
              s(i) match
                case ',' => i += 1
                case ']' => i += 1; more = false
                case _ => fail("',' or ']'")
          Arr(xs.toList)
        case '"' => Str(string())
        case 't' if s.startsWith("true", i) => i += 4; Bool(true)
        case 'f' if s.startsWith("false", i) => i += 5; Bool(false)
        case 'n' if s.startsWith("null", i) => i += 4; Null
        case _ =>
          val start = i
          while i < s.length && "+-0123456789.eE".contains(s(i)) do i += 1
          if start == i then fail("a value")
          Num(BigDecimal(s.substring(start, i)))
    def string(): String =
      if s(i) != '"' then fail("a text")
      i += 1
      val b = StringBuilder()
      while s(i) != '"' do
        if s(i) == '\\' then
          i += 1
          s(i) match
            case 'n' => b += '\n'
            case 't' => b += '\t'
            case 'r' => b += '\r'
            case 'b' => b += '\b'
            case 'f' => b += '\f'
            case 'u' => b += Integer.parseInt(s.substring(i + 1, i + 5), 16).toChar; i += 4
            case c => b += c
        else b += s(i)
        i += 1
      i += 1
      b.toString
    try
      val v = value(); ws()
      if i != s.length then Left(s"more after the value at $i") else Right(v)
    catch case e: Exception => Left(e.getMessage)
