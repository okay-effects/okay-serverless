package okay.serverless

import okay.scala3.*

/**
 * STORAGE IN MEMORY, the contract kept as a cloud keeps it — and faults on demand, so a program can be shown right
 * under them: a crash before or after a commit, another writer between a step's reads and its commit, an eviction
 * (a new handler — a new instance — on the same storage).
 */
final class Memory:
  private val values = scala.collection.mutable.Map.empty[String, Versioned]
  @volatile var alarm: Option[Long] = None
  /** run before a commit is checked: another writer's moment */
  @volatile var beforeCommit: () => Unit = () => ()
  /** a crash after the next commit: the step committed, its caller never told */
  @volatile var crashAfterCommit = false
  /** a crash at the next commit, before anything is written */
  @volatile var crashBeforeCommit = false
  @volatile var commits = 0

  def snapshot: Map[String, String] = synchronized(values.view.mapValues(_.value).toMap)

  /** another writer: a value written outside any step */
  def write(key: String, value: String): Unit = synchronized {
    values(key) = Versioned(value, values.get(key).fold(1L)(_.version + 1))
  }

  def read(keys: List[String]): Map[String, Versioned] = synchronized(keys.flatMap(k => values.get(k).map(k -> _)).toMap)

  def keys(prefix: String): List[String] = synchronized(values.keys.filter(_.startsWith(prefix)).toList.sorted)

  def commit(read: Map[String, Long], writes: Map[String, Option[String]], at: Option[Long]): Boolean =
    val hook = beforeCommit
    beforeCommit = () => ()
    hook()
    if crashBeforeCommit then
      crashBeforeCommit = false
      throw Crash("before the commit")
    val ok = synchronized {
      val same = read.forall((k, v) => values.get(k).fold(0L)(_.version) == v)
      if same then
        for (k, w) <- writes do w match
          case Some(v) => values(k) = Versioned(v, values.get(k).fold(1L)(_.version + 1))
          case None => values.remove(k)
        at.foreach(a => alarm = Some(alarm.fold(a)(math.min(_, a))))
        commits += 1
      same
    }
    if ok && crashAfterCommit then
      crashAfterCommit = false
      throw Crash("after the commit")
    ok

  /** the handler of `Storage` on this memory: one instance of the program */
  def handler[R[_], Rest[_], A](p: A ! R)(using Split[Storage, R, Rest]): A ! Rest =
    handle(p, Handler.tail[Storage, Rest, A, A](pure)([X] => (op: Storage[X]) => op match
      case Storage.Read(keys) => read(keys)
      case Storage.Keys(prefix) => keys(prefix)
      case Storage.Commit(r, w, a) => commit(r, w, a)))

/** an instance gone mid-step: what the platform would see as the process dying */
final class Crash(where: String) extends RuntimeException(s"crashed $where", null, false, false)
