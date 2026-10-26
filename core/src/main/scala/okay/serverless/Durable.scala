package okay.serverless

import okay.scala3.*

/** a stored value and its version: what a commit checks (0: absent) */
final case class Versioned(value: String, version: Long)

/**
 * WHAT A PLATFORM'S STORAGE DOES — and nothing more: values by key with their versions, keys by prefix, and a commit
 * of a step's writes, all at once, if what it read is as it was. One handler a platform: a Durable Object, DynamoDB,
 * memory.
 */
enum Storage[X]:
  case Read(keys: List[String]) extends Storage[Map[String, Versioned]]
  case Keys(prefix: String) extends Storage[List[String]]
  /** the writes (`None`: deleted) and the alarm, atomically — if each key read is still at the version read
   * (0: absent);
   * `false`: something read has changed, nothing written */
  case Commit(read: Map[String, Long], writes: Map[String, Option[String]], alarm: Option[Long])
    extends Storage[Boolean]

/** THE PROGRAM'S WORDS for durable state: what a program says, whatever cloud it runs in (specs/durable.md) */
enum Durable[X]:
  case Get(key: String) extends Durable[Option[String]]
  case Put(key: String, value: String) extends Durable[Unit]
  case Delete(key: String) extends Durable[Unit]
  case Keys(prefix: String) extends Durable[List[String]]
  case WakeAt(ms: Long) extends Durable[Unit]
  /** the idempotency key of this step for `name`: the same each time the step runs, another the next step */
  case Key(name: String) extends Durable[String]
  /** a value `once` kept for a key, in this step's runs */
  case Kept(key: String) extends Durable[Option[String]]
  case Keep(key: String, value: String) extends Durable[Unit]

object Durable:
  def get[A, R[_]](key: String)(using c: Codec[A], at: In[Durable, R]): Option[A] ! R =
    perform[Durable](Get(key))
      .map(_.map(s => c.decode(s).fold(e => throw IllegalStateException(s"$key: $e"), identity)))
  def put[A, R[_]](key: String, a: A)(using c: Codec[A], at: In[Durable, R]): Unit ! R =
    perform[Durable](Put(key, c.encode(a)))
  def delete[R[_]](key: String)(using In[Durable, R]): Unit ! R = perform[Durable](Delete(key))
  def keys[R[_]](prefix: String)(using In[Durable, R]): List[String] ! R = perform[Durable](Keys(prefix))
  def wakeAt[R[_]](ms: Long)(using In[Durable, R]): Unit ! R = perform[Durable](WakeAt(ms))

  /**
   * `f`'s value, `f` given this step's idempotency key for `name`: run once in this step — a step run again (a
   * conflict) gets the value kept, not `f` again; a step run again after a crash runs `f` again with the SAME key, so
   * the outside world can tell it is the same question
   */
  def once[A, R[_]](name: String)(f: String => A ! R)(using c: Codec[A], at: In[Durable, R]): A ! R =
    for
      key <- perform[Durable](Key(name))
      kept <- perform[Durable](Kept(key))
      a <- kept match
        case Some(s) => pure[A, R](c.decode(s).fold(e => throw IllegalStateException(s"$name: $e"), identity))
        case None => f(key).flatMap(a => perform[Durable](Keep(key, c.encode(a))).as(a))
    yield a

  /** how many times a step may be run again after conflicts before it gives up */
  val tries = 20

  /**
   * A STEP, A TRANSACTION: `p` against a snapshot — reads what was committed, and its own writes; its writes kept
   * here and committed at its end, all at once with its alarm and the step's count; nothing written if it fails or
   * throws; RUN AGAIN from its start if the commit finds what it read changed. `scope` names the step's process: its
   * count of committed steps (`scope/#steps`) makes the idempotency keys of `once`.
   */
  def step[R[_], Rest[_], A](scope: String)(p: A ! R)(using Split[Durable, R, Rest], In[Storage, Rest]): A ! Rest =
    defer {
      // what `once` kept, across this step's runs — made when the step runs, each time it runs
      val kept = scala.collection.mutable.Map.empty[String, String]
      loop[Int, A, Rest](0) { attempt =>
        if attempt >= tries then throw IllegalStateException(s"$scope: $tries conflicts, the step given up")
        run1[R, Rest, A](scope, p, kept).map {
          case Some(a) => Right(a)
          case None => Left(attempt + 1)
        }
      }
    }

  /** one run of a step: its value if committed, `None` if a conflict stopped the commit */
  private def run1[R[_], Rest[_], A](scope: String, p: A ! R, kept: scala.collection.mutable.Map[String, String])(
    using Split[Durable, R, Rest], In[Storage, Rest]): Option[A] ! Rest =
    val countKey = s"$scope/#steps"
    perform[Storage](Storage.Read(List(countKey))).flatMap { got =>
      val count = got.get(countKey)
      val steps = count.fold(0L)(_.value.toLong)
      // the snapshot read so far (key → value and version), the writes, the alarm
      val read = scala.collection.mutable.Map.empty[String, Option[Versioned]]
      read(countKey) = count
      val writes = scala.collection.mutable.LinkedHashMap.empty[String, Option[String]]
      var alarm: Option[Long] = None
      def seen(key: String): Option[String] ! Rest =
        writes.get(key) match
          case Some(w) => pure(w)
          case None => read.get(key) match
            case Some(v) => pure(v.map(_.value))
            case None => perform[Storage](Storage.Read(List(key))).map { m =>
              read(key) = m.get(key)
              m.get(key).map(_.value)
            }
      handle(p, Handler.general[Durable, Rest, A, Option[A]] { a =>
        val versions = read.map((k, v) => k -> v.fold(0L)(_.version)).toMap
        val all = writes.toMap + (countKey -> Some((steps + 1).toString))
        perform[Storage](Storage.Commit(versions, all, alarm)).map(ok => Option.when(ok)(a))
      }([X] => (op: Durable[X], k: X => Option[A] ! Rest) => op match
        case Get(key) => seen(key).flatMap(k)
        case Put(key, value) =>
          writes(key) = Some(value)
          k(())
        case Delete(key) =>
          writes(key) = None
          k(())
        case Keys(prefix) => perform[Storage](Storage.Keys(prefix)).flatMap { stored =>
          val added = writes.collect { case (key, Some(_)) if key.startsWith(prefix) => key }
          val deleted = writes.collect { case (key, None) => key }.toSet
          k((stored ++ added).distinct.filterNot(deleted).filterNot(_.endsWith("/#steps")).sorted)
        }
        case WakeAt(ms) =>
          alarm = Some(alarm.fold(ms)(math.min(_, ms)))
          k(())
        case Key(name) => k(s"$scope/${steps + 1}/$name")
        case Kept(key) => k(kept.get(key))
        case Keep(key, value) =>
          kept(key) = value
          k(())))
    }

  /**
   * A DURABLE PROCESS: its state `S` (data, by its codec) under `name/state`, `init` until a step has committed one;
   * each event one step — the state loaded, `step` run, the new state committed. Restarted anywhere, the next event
   * finds the state of the last step that committed
   */
  def machine[S, E, R[_], Rest[_]](name: String, init: S)(step: (S, E) => S ! R)(event: E)(
    using c: Codec[S], d: Split[Durable, R, Rest], at: In[Durable, R], s: In[Storage, Rest]): S ! Rest =
    Durable.step[R, Rest, S](name)(
      get[S, R](s"$name/state").flatMap(st => step(st.getOrElse(init), event))
        .flatMap(next => put[S, R](s"$name/state", next).as(next)))
