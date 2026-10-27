package okay.serverless.store

import okay.effects.shape.Shape
import okay.scala3.*
import okay.store.Mapping

/** ONE PROGRAM over a mapped type, its answers known: every backend must give them */
object StorePrograms:
  final case class Sale(id: String, item: String, qty: Int, tags: List[String])
  val sales: Mapping[Sale] = Mapping.keyed("sales", Shape.derived[Sale])(_.id)
  type S[X] = Repo.Of[Sale][X]

  /** sales made with keys from the store and one saved by its own; found, counted, removed */
  def program[R[_]](using In[S, R]): (List[String], Long, Option[Sale], List[String], Option[String]) ! R =
    for
      tea <- Repo.create[Sale, R](k => Sale(k, "tea", 3, List("hot")))
      _ <- Repo.save[Sale, R](Sale("x1", "coffee", 1, Nil))
      green <- Repo.create[Sale, R](k => Sale(k, "green tea", 5, List("hot", "green")))
      teas <- Repo.find[Sale, R](sales.at(_.item) contains "TEA", List(sales.at(_.qty).desc))
      n <- Repo.count[Sale, R](sales.at(_.qty) > 2)
      _ <- Repo.remove[Sale, R]("x1")
      gone <- Repo.load[Sale, R]("x1")
      hot <- Repo.find[Sale, R](sales.at(_.tags) has "green")
      again <- Repo.load[Sale, R](tea.id).map(_.map(_.item))
    yield (teas.map(_.item), n, gone, hot.map(_.id), again)

  val expected = (List("green tea", "tea"), 2L, None, List("000000000002"), Some("tea"))

  /** without keys from the store: for KV */
  def saving[R[_]](using In[S, R]): (List[String], Long) ! R =
    for
      _ <- Repo.save[Sale, R](Sale("a", "tea", 3, Nil))
      _ <- Repo.save[Sale, R](Sale("b", "green tea", 5, Nil))
      _ <- Repo.save[Sale, R](Sale("c", "coffee", 1, Nil))
      teas <- Repo.find[Sale, R](sales.at(_.item) contains "tea", List(sales.at(_.qty).desc), Some(1))
      n <- Repo.count[Sale, R](sales.at(_.qty) < 4)
    yield (teas.map(_.id), n)
