package okay.serverless.host

import okay.effects.json.{Descent, Json}
import okay.http.{Request, Response}
import okay.serverless.cloud.{Ai, JdkFetch, Port}

import scala.concurrent.{ExecutionContext, Future}

/** a server with OpenAI's API: where, its key, and the models a program names mapped to the server's own */
final case class AiServer(url: String, key: Option[String] = None, models: Map[String, String] = Map.empty)

/**
 * `Ai` BY ANY SERVER WITH OPENAI'S API (specs/cloud.md "The host"): okay-llm's gateway, Ollama, vLLM, a remote one.
 * Workers AI's shapes in and out, so a program does not tell: a chat (`messages`, or a `prompt`) goes to
 * `/v1/chat/completions` and is answered `{"response": text}`; a text (`text`, one or several) goes to
 * `/v1/embeddings` and is answered `{"data": [[...], ...]}`; a translation (`text` with `target_lang` and
 * `source_lang`, m2m100's) is a chat asking for the translation alone, answered `{"translated_text": text}`. A model's
 * name is the configuration's mapping of it, or itself.
 */
final class OpenAiAi(server: AiServer, fetch: JdkFetch)(using ec: ExecutionContext) extends Port[Ai]:
  private val base = server.url.stripSuffix("/")

  private def post(path: String, body: Json): Future[Json] =
    val headers = Map("content-type" -> List("application/json")) ++
      server.key.map(k => "authorization" -> List(s"Bearer $k"))
    fetch.send(base + path, Request("POST", path, Map.empty, headers, Json.print(body))).flatMap { (r: Response) =>
      if r.status / 100 != 2 then Future.failed(RuntimeException(s"AI ${r.status}: ${r.body.take(300)}"))
      else Descent.parse(r.body).fold(e => Future.failed(RuntimeException(s"AI: ${e.message}")), Future.successful)
    }

  private def field(j: Json, k: String): Option[Json] = j.field(k)

  def apply[X](op: Ai[X]): Future[X] = op match
    case Ai.Attempt(model, input) => Ai.attempted(apply(Ai.Run(model, input)))
    case Ai.Run(model, input) =>
      val name = Json.Str(server.models.getOrElse(model, model))
      (field(input, "messages"), field(input, "prompt"), field(input, "text")) match
        case (Some(messages), _, _) => chat(name, messages, input)
        case (None, Some(prompt), _) =>
          chat(name, Json.arr(Json.obj("role" -> Json.Str("user"), "content" -> prompt)), input)
        case (None, None, Some(text)) if field(input, "target_lang").isDefined => translation(name, text, input)
        case (None, None, Some(text)) =>
          val texts = text match
            case a: Json.Arr => a
            case one => Json.arr(one)
          post("/v1/embeddings", Json.obj("model" -> name, "input" -> texts)).map { j =>
            val rows = field(j, "data").collect { case Json.Arr(xs) => xs }.getOrElse(Vector.empty)
              .flatMap(x => field(x, "embedding"))
            Json.obj("shape" -> Json.arr(Json.num(rows.size), Json.num(rows.headOption.collect {
              case Json.Arr(v) => v.size }.getOrElse(0))), "data" -> Json.Arr(rows))
          }
        case _ => Future.failed(IllegalArgumentException(s"AI: no messages, prompt or text in $input"))

  /** a translation as a chat: the languages by their English names, the answer the translation alone (a model's
   * `<think>` block dropped) */
  private def translation(model: Json, text: Json, input: Json): Future[Json] =
    def lang(k: String): String = field(input, k).collect { case Json.Str(c) => c }.map(c =>
      Option(java.util.Locale.forLanguageTag(c).getDisplayLanguage(java.util.Locale.ENGLISH)).filter(_.nonEmpty)
        .getOrElse(c)).getOrElse("the source language")
    val source = text match
      case Json.Str(t) => t
      case other => Json.print(other)
    val messages = Json.arr(
      Json.obj("role" -> Json.Str("system"), "content" -> Json.Str(
        "You are a translator. Answer with the translation of the user's text alone: no quotes, no notes.")),
      Json.obj("role" -> Json.Str("user"), "content" -> Json.Str(
        s"Translate from ${lang("source_lang")} to ${lang("target_lang")}:\n\n$source")))
    post("/v1/chat/completions", Json.obj("model" -> model, "messages" -> messages)).map { j =>
      val content = field(j, "choices").collect { case Json.Arr(cs) => cs }.flatMap(_.headOption)
        .flatMap(c => field(c, "message")).flatMap(m => field(m, "content")).collect { case Json.Str(s) => s }
        .getOrElse("")
      Json.obj("translated_text" -> Json.Str(content.replaceAll("(?s)<think>.*?</think>", "").trim))
    }

  /** a chat: the rest of the input passed as it is (max_tokens, temperature, chat_template_kwargs, tools…) */
  private def chat(model: Json, messages: Json, input: Json): Future[Json] =
    val rest = input match
      case Json.Obj(fs) => fs.filterNot((k, _) => k == "messages" || k == "prompt" || k == "stream")
      case _ => Vector.empty
    post("/v1/chat/completions", Json.Obj(Vector("model" -> model, "messages" -> messages) ++ rest)).map { j =>
      val message = field(j, "choices").collect { case Json.Arr(cs) => cs }.flatMap(_.headOption)
        .flatMap(c => field(c, "message"))
      val text = message.flatMap(m => field(m, "content")).collect { case Json.Str(s) => s }.getOrElse("")
      Json.Obj(Vector("response" -> Json.Str(text)) ++
        message.flatMap(m => field(m, "tool_calls")).map("tool_calls" -> _) ++ field(j, "usage").map("usage" -> _))
    }
