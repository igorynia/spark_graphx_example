package org.example.graphx

import org.apache.spark.SparkContext.rddToPairRDDFunctions
import org.apache.spark.graphx.{VertexId, PartitionStrategy, Graph, Edge}
import org.apache.spark.rdd.RDD

object CreateGraph extends App {

  val sc = SparkProvider.local

  println("===========================================================")

  val athleteType = 'athlete
  val countryType = 'country
  val sportType = 'sport
  val yearType = 'year

  val csvKeys = athleteType :: 'age :: countryType :: yearType :: 'closing_date :: sportType :: 'gold_m :: 'silver_m :: 'bronze_m :: 'total_m :: Nil

  try {
    val csvRows: RDD[Map[NodeType, String]] = sc.textFile(getClass.getClassLoader.getResource("OlympicAthletes_0.csv").getFile)
      .map(line => (csvKeys zip line.split(";")).toMap)
      .filter(row => row(athleteType) != "Athlete") // Skip header

    def nodesByTypeFromRows(csvRows: RDD[Map[Symbol, String]], nodeType: Symbol): RDD[Node] = {
      csvRows
        .map(row => row.filterKeys(key => nodeType == key))
        .distinct()
        .map(props => new TypedNode(nodeType, props))
    }

    // Constructing nodes and assigning IDs
    val vertices: RDD[(Node, VertexId)] = (
      nodesByTypeFromRows(csvRows, athleteType)
        ++ nodesByTypeFromRows(csvRows, countryType)
        ++ nodesByTypeFromRows(csvRows, sportType)
        ++ nodesByTypeFromRows(csvRows, yearType)
      )
      .zipWithUniqueId()

    def filterVerticesByType(nodeType: NodeType): RDD[(String, VertexId)] = vertices.collect {
      case (node, id) if node.hasType(nodeType) => node.properties(nodeType).toString -> id
    }

    val athleteNamesWithCsvRows: RDD[(String, (Map[NodeType, String], VertexId))] = csvRows
      .map(row => row(athleteType) -> row)
      .join(filterVerticesByType(athleteType))

    // Constructing edges between athlete nodes and connected data
    val edges: List[RDD[Edge[Int]]] = for {
      nodeType <- countryType :: sportType :: yearType :: Nil
    } yield {
      athleteNamesWithCsvRows
        .map { case (_, (csvRow, athleteId)) => csvRow(nodeType) -> athleteId}
        .join(filterVerticesByType(nodeType))
        .map { case (_, (athleteId, nodeId)) => Edge(athleteId, nodeId, 0)}
    }

    val originalGraph: Graph[Node, Int] = Graph(vertices.map(_.swap), edges.reduce(_ ++ _), EmptyNode)
      .partitionBy(PartitionStrategy.RandomVertexCut)
      .groupEdges(_ + _)

    println(s"Vertices count is ${originalGraph.vertices.count()}")
    println(s"Edges count is ${originalGraph.edges.count()}")

    println("===========================================================")

    val nodesWithGroupedAttributes: RDD[(Long, Map[NodeType, Map[VertexId, Double]])] = originalGraph.triplets
      .filter(_.srcAttr.hasType(athleteType))
      .groupBy(_.srcId)
      .mapValues(triplets =>
        triplets
          .groupBy(_.dstAttr.nodeType)
          .mapValues(grouped => grouped.map(_.dstId -> 1D).toMap)
      )

    nodesWithGroupedAttributes.cache()

    val pairs = nodesWithGroupedAttributes.cartesian(nodesWithGroupedAttributes)
      .filter { case ((aId, _), (bId, _)) => aId < bId}
      .flatMap { case ((aId, aAttrs), (bId, bAttrs)) =>
        Iterable(Edge(aId, bId, 0))
      }
    println(s"Pairs count ${pairs.count()}")
  } catch {
    case e: Exception => e.printStackTrace()
  } finally {
    sc.stop()
  }

}
