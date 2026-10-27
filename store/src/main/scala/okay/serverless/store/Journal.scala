package okay.serverless.store

import okay.effects.cek.{Into, OnCek}
import okay.effects.{Sum, Void}
import okay.scala3.*
import okay.store.{Data, Store, Stores}

/**
 * OKAY-STORE'S JOURNALED RECORDS IN THE CLOUD (`Store`: kinds, versions, the journal, the audit): the program written
 * with okay-store's own words (`Journal.stores`), on okay-simple's interface; okay-store's own handler
 * (`Stores.onData`) translates it into `Data`; okay-simple's bridge (`OnCek.into`) puts it into the cloud program's
 * row, where `Data` is handled as any (`StoreOn.sql`, `.kv`, `.durable`). One machine, one run: in an object, inside a
 * `Durable.step`, a record, its version and its event are one transaction with the rest of the step.
 */
object Journal:
  /** okay-store's words, on okay-simple's interface on okay-scala's machine */
  val stores: Stores[OnCek.type] = Stores()(using OnCek)
  /** the journal program's row, okay-simple's: `Data` under `Store` */
  type Below[X] = Sum[Void, Data, X]
  type Records[X] = Sum[Below, Store, X]

  /** the records program as a program of `R`, its `Data` there; `ids` and `now` the records' ids and times */
  def apply[R[_], A](ids: () => String, now: () => Long)(p: OnCek.![A, Records])(using Into[Below, R]): A ! R =
    OnCek.into[R](stores.onData(ids, now)[Records, Below, A](p))
