package okay.serverless.simple

import okay.effects.Effects

import scala.collection.mutable

/**
 * DURABLE STATE ON okay-simple — okay-serverless's twin for Scala 2.13 and 3 (specs/durable.md): the same words, the
 * same step (a transaction against a snapshot, committed at its end, run again on a conflict), the same machine and
 * `once`, the same STORED FORMAT (`<scope>/#steps`, `<name>/state`, values as JSON text) — so state written by one is
 * read by the other. Over any implementation: `val s = Serverless(); import s._`.
 */
class Serverless[F <: Effects with Singleton](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def get[A, R[_]](key: String)(implicit c: Codec[A], at: In[Durable, R]): Option[A] ! R =
    perform[Durable].apply[R, Option[String]](Durable.Get(key, v => v))
      .map(_.map(s => c.decode(s).fold(e => throw new IllegalStateException(s"$key: $e"), a => a)))
  def put[A, R[_]](key: String, a: A)(implicit c: Codec[A], at: In[Durable, R]): Unit ! R =
    perform[Durable].apply[R, Unit](Durable.Put(key, c.encode(a), u => u))
  def delete[R[_]](key: String)(implicit at: In[Durable, R]): Unit ! R =
    perform[Durable].apply[R, Unit](Durable.Delete(key, u => u))
  def keys[R[_]](prefix: String)(implicit at: In[Durable, R]): List[String] ! R =
    perform[Durable].apply[R, List[String]](Durable.Keys(prefix, ks => ks))
  def wakeAt[R[_]](ms: Long)(implicit at: In[Durable, R]): Unit ! R =
    perform[Durable].apply[R, Unit](Durable.WakeAt(ms, u => u))

  /**
   * `f`'s value, `f` given this step's idempotency key for `name`: run once in this step — a step run again (a
   * conflict) gets the value kept; a step run again after a crash runs `f` again with the SAME key
   */
  def once[A, R[_]](name: String)(f: String => A ! R)(implicit c: Codec[A], at: In[Durable, R]): A ! R =
    perform[Durable].apply[R, String](Durable.Key(name, k => k)).flatMap { key =>
      perform[Durable].apply[R, Option[String]](Durable.Kept(key, v => v)).flatMap {
        case Some(s) => pure[A, R](c.decode(s).fold(e => throw new IllegalStateException(s"$name: $e"), a => a))
        case None => f(key).flatMap(a => perform[Durable].apply[R, Unit](Durable.Keep(key, c.encode(a), u => u)).as(a))
      }
    }

  /** how many times a step may be run again after conflicts before it gives up */
  val tries = 20

  private def storage[X, R[_]](op: Storage[X])(implicit in: In[Storage, R]): X ! R = perform[Storage].apply[R, X](op)

  /**
   * A STEP, A TRANSACTION: `p` against a snapshot — reads what was committed, and its own writes; its writes
   * committed at its end, all at once with its alarm and the step's count; nothing written if it fails or throws; RUN
   * AGAIN from its start if the commit finds what it read changed
   */
  def step[R[_], Rest[_], A](scope: String)(p: A ! R)(implicit sp: Split[Durable, R, Rest],
                                                      st: In[Storage, Rest]): A ! Rest =
    delay {
      // what `once` kept, across this step's runs
      val kept = mutable.Map.empty[String, String]
      tailRecM[Int, A, Rest](0) { attempt =>
        if (attempt >= tries) throw new IllegalStateException(s"$scope: $tries conflicts, the step given up")
        run1[R, Rest, A](scope, p, kept).map[Either[Int, A]] {
          case Some(a) => Right(a)
          case None => Left(attempt + 1)
        }
      }
    }

  /** one run of a step: its value if committed, `None` if a conflict stopped the commit */
  private def run1[R[_], Rest[_], A](scope: String, p: A ! R, kept: mutable.Map[String, String])(
    implicit sp: Split[Durable, R, Rest], st: In[Storage, Rest]): Option[A] ! Rest = {
    val countKey = s"$scope/#steps"
    storage[Map[String, Versioned], Rest](Storage.Read(List(countKey), m => m)).flatMap { got =>
      val count = got.get(countKey)
      val steps = count.fold(0L)(_.value.toLong)
      val read = mutable.Map[String, Option[Versioned]](countKey -> count)
      val writes = mutable.LinkedHashMap.empty[String, Option[String]]
      var alarm: Option[Long] = None
      def seen(key: String): Option[String] ! Rest =
        writes.get(key) match {
          case Some(w) => pure[Option[String], Rest](w)
          case None => read.get(key) match {
              case Some(v) => pure[Option[String], Rest](v.map(_.value))
              case None => storage[Map[String, Versioned], Rest](Storage.Read(List(key), m => m)).map { m =>
                  read(key) = m.get(key)
                  m.get(key).map(_.value)
                }
            }
        }
      handle[Durable, R, Rest, A, Option[A]](p, new Handler[Durable, Rest, A, Option[A]] {
        def ret(a: A): Option[A] ! Rest = {
          val versions = read.map { case (k, v) => k -> v.fold(0L)(_.version) }.toMap
          val all = writes.toMap + (countKey -> Some((steps + 1).toString))
          storage[Boolean, Rest](Storage.Commit(versions, all, alarm, ok => ok)).map(ok => if (ok) Some(a) else None)
        }
        def op[X](op: Durable[X], k: X => Option[A] ! Rest): Option[A] ! Rest = op match {
          case Durable.Get(key, answer) => seen(key).flatMap(v => k(answer(v)))
          case Durable.Put(key, value, answer) =>
            writes(key) = Some(value)
            k(answer(()))
          case Durable.Delete(key, answer) =>
            writes(key) = None
            k(answer(()))
          case Durable.Keys(prefix, answer) =>
            storage[List[String], Rest](Storage.Keys(prefix, ks => ks)).flatMap { stored =>
              val added = writes.collect { case (key, Some(_)) if key.startsWith(prefix) => key }
              val deleted = writes.collect { case (key, None) => key }.toSet
              k(answer((stored ++ added).distinct.filterNot(deleted).filterNot(_.endsWith("/#steps")).sorted))
            }
          case Durable.WakeAt(ms, answer) =>
            alarm = Some(alarm.fold(ms)(math.min(_, ms)))
            k(answer(()))
          case Durable.Key(name, answer) => k(answer(s"$scope/${steps + 1}/$name"))
          case Durable.Kept(key, answer) => k(answer(kept.get(key)))
          case Durable.Keep(key, value, answer) =>
            kept(key) = value
            k(answer(()))
        }
      })
    }
  }

  /**
   * A DURABLE PROCESS: its state `S` under `name/state`, `init` until a step has committed one; each event one step.
   * Restarted anywhere, the next event finds the state of the last step that committed
   */
  def machine[S, E, R[_], Rest[_]](name: String, init: S)(step: (S, E) => S ! R)(event: E)(
    implicit c: Codec[S], sp: Split[Durable, R, Rest], st: In[Storage, Rest]): S ! Rest =
    this.step[R, Rest, S](name)(
      get[S, R](s"$name/state").flatMap(s => step(s.getOrElse(init), event))
        .flatMap(next => put[S, R](s"$name/state", next).as(next)))

  /** the handler of `Storage` on a memory: one instance of the program */
  def memory[R[_], Rest[_], A](m: Memory)(p: A ! R)(implicit sp: Split[Storage, R, Rest]): A ! Rest =
    handle[Storage, R, Rest, A, A](p, new TailHandler[Storage, Rest, A, A] {
      def ret(a: A): A ! Rest = pure[A, Rest](a)
      def answer[X](op: Storage[X]): X = op match {
        case Storage.Read(keys, answer) => answer(m.read(keys))
        case Storage.Keys(prefix, answer) => answer(m.keys(prefix))
        case Storage.Commit(r, w, a, answer) => answer(m.commit(r, w, a))
      }
    })
}

object Serverless {
  def apply()(implicit fx: Effects): Serverless[fx.type] = {
    implicit val exact: fx.type = fx
    new Serverless[fx.type]
  }
}
