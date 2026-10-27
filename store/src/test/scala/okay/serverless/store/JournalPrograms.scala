package okay.serverless.store

import okay.effects.cek.OnCek
import okay.effects.json.Json
import okay.effects.shape.Schema
import okay.store.{Kind, KindId, Record}

/** ONE PROGRAM OF RECORDS, written with okay-store's own words; its answers okay-store's own handler in memory gives
 * (`reference`) — every backend in the cloud must give the same */
object JournalPrograms:
  import Journal.{Records, stores}
  import stores.*

  val passport: Kind = Kind(KindId("passport"), "Passport", Schema.Record(Vector(
    Schema.Field("number", Schema.Text(Nil), Some("Number"), None, true, None),
    Schema.Field("country", Schema.Text(Nil), Some("Country"), None, false, None))), Set("number"), List("number"))
  def ids(): () => String = { var n = 0; () => { n += 1; s"id$n" } }
  def clock(): () => Long = { var t = 1000L; () => { t += 1; t } }

  type Out = (List[String], List[Record], List[Record], Option[Record], Int)
  val program: OnCek.![Out, Records] = OnCek.flatMap(define[Records](passport))(_ =>
    OnCek.flatMap(add[Records](passport.id, Json.obj("number" -> Json.Str("AB123"), "country" -> Json.Str("UA")),
      tags = Set("travel")))(r =>
      OnCek.flatMap(revise[Records](r.id, Json.obj("number" -> Json.Str("AB124"), "country" -> Json.Str("UA")),
        tags = Set("travel"), note = Some("renewed")))(_ =>
        OnCek.flatMap(audit[Records](r.id, "number", "owner"))(_ =>
          OnCek.flatMap(kinds[Records])(ks =>
            OnCek.flatMap(find[Records]("ab124", Some("travel")))(found =>
              OnCek.flatMap(history[Records](r.id))(h =>
                OnCek.flatMap(get[Records](r.id))(now =>
                  OnCek.map(journaled[Records])(j => (ks.map(_.name), found, h, now, j._1.size))))))))))

  /** okay-store's own answer: its handler on its own `Data` in memory, on the same machine */
  def reference: Out =
    val datas = okay.store.Datas()(using OnCek)
    OnCek.run(datas.on(okay.store.Memory())[Journal.Below, OnCek.Pure, Out](stores.onData(ids(), clock())(program)))
