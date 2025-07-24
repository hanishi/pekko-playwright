package adtech.taxonomy

import scala.annotation.tailrec
import scala.collection.mutable
import scala.io.Source
import scala.util.Using

case class TieredCategory(
    id: String,
    name: String,
    parent: Option[TieredCategory],
    children: mutable.Map[String, TieredCategory],
) {
  override def toString: String = parent
    .fold(s"$name($id)")(p => s"$name($id) -> $p")

  override def hashCode(): Int = id.hashCode
}

object TieredCategory {
  private val nodeMap = mutable.Map[String, TieredCategory]()

  def getAncestors(id: String): List[TieredCategory] = nodeMap.get(id)
    .map { node =>
      @tailrec
      def loop(
          current: TieredCategory,
          visited: Set[String],
          acc: List[TieredCategory],
      ): List[TieredCategory] = current.parent match {
        case Some(p) if !visited.contains(p.id) =>
          loop(p, visited + p.id, p :: acc)
        case _ => acc
      }
      loop(node, Set.empty, Nil).reverse
    }.getOrElse(Nil)

  loadCategoriesFromTSV("/iab_content_taxonomy/3_1.tsv")
  loadCategoriesFromTSV("/iab_content_taxonomy/3_0_descriptive_vectors.tsv")

  def getAllDescendants(id: String): List[TieredCategory] = nodeMap.get(id)
    .map { node =>
      @tailrec
      def loop(
          stack: List[TieredCategory],
          visited: Set[String],
          acc: List[TieredCategory],
      ): List[TieredCategory] = stack match {
        case Nil => acc
        case head :: tail if !visited.contains(head.id) =>
          loop(
            head.children.values.toList ++ tail,
            visited + head.id,
            head :: acc,
          )
        case _ :: tail => loop(tail, visited, acc)
      }
      loop(node.children.values.toList, Set.empty, Nil).reverse
    }.getOrElse(Nil)

  private def loadCategoriesFromTSV(resourcePath: String): Unit =
    Using(Source.fromURL(getClass.getResource(resourcePath))) { source =>
      for (line <- source.getLines().drop(1)) {
        val columns = line.split("\t").map(_.trim)
        if (columns.length >= 3) {
          val id = columns(0)
          val parentId = if (columns(1).nonEmpty) Some(columns(1)) else None
          val name = columns(2)
          insert(id, name, parentId)
        }
      }
    }

  private def insert(id: String, name: String, parentId: Option[String]): Unit = {
    val parent = parentId.flatMap(nodeMap.get)
    val newNode = TieredCategory(id, name, parent, mutable.Map.empty)
    parent.foreach(_.children(id) = newNode)
    nodeMap(id) = newNode
  }
}
