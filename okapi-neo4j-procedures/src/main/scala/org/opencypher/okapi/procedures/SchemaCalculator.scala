/*
 * Copyright (c) 2016-2018 "Neo4j Sweden, AB" [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Attribution Notice under the terms of the Apache License 2.0
 *
 * This work was created by the collective efforts of the openCypher community.
 * Without limiting the terms of Section 6, any Derivative Work that is not
 * approved by the public consensus process of the openCypher Implementers Group
 * should not be described as “Cypher” (and Cypher® is a registered trademark of
 * Neo4j Inc.) or as "openCypher". Extensions by implementers or prototypes or
 * proposals for change that have been documented or implemented should only be
 * described as "implementation extensions to Cypher" or as "proposed changes to
 * Cypher that are not yet approved by the openCypher community".
 */
package org.opencypher.okapi.procedures

import java.util
import java.util.concurrent._
import java.util.stream.Stream

import org.neo4j.internal.kernel.api._
import org.neo4j.kernel.api.KernelTransaction
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge
import org.neo4j.kernel.impl.storageengine.impl.recordstorage.RecordStorageEngine
import org.neo4j.kernel.internal.GraphDatabaseAPI
import org.neo4j.logging.Log
import org.opencypher.okapi.api.types.CTNull
import org.opencypher.okapi.api.types.CypherType._
import org.opencypher.okapi.api.value.CypherValue

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class SchemaCalculator(api: GraphDatabaseAPI, tx: KernelTransaction, log: Log) {
  val threads: Int = Runtime.getRuntime.availableProcessors
  implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(threads))

  val ctx: ThreadToStatementContextBridge =  api.getDependencyResolver.resolveDependency(classOf[ThreadToStatementContextBridge])

  /**
    * Computes the schema of the Neo4j graph as used by Okapi
    */
  def constructOkapiSchemaInfo(): Stream[OkapiSchemaInfo] = {

    val nodeSchemaFutures = computeEntitySchema(Node)
    val relationshipSchemaFutures = computeEntitySchema(Relationship)

    val nodesSchema = nodeSchemaFutures
      .map(Await.ready(_, Duration.apply(20, TimeUnit.SECONDS)))
      .map(_.value.get.get)
      .foldLeft(new LabelPropertyKeyMap)(_ ++ _)

    val relationshipsSchema = relationshipSchemaFutures
      .map(Await.ready(_, Duration.apply(20, TimeUnit.SECONDS)))
      .map(_.value.get.get)
      .foldLeft(new LabelPropertyKeyMap)(_ ++ _)

    val nodeStream = getOkapiSchemaInfo(Node, nodesSchema)

    val relStream = getOkapiSchemaInfo(Relationship, relationshipsSchema)

    Stream.concat(nodeStream, relStream)
  }

  /**
    * Computes the entity schema for the given entities by computing the schema for each individual entity and then
    * combining them. Uses batching to parallel the computation
    */
  private def computeEntitySchema[T <: WrappedCursor](typ: EntityType): Seq[Future[LabelPropertyKeyMap]] = {
    val maxId = getHighestIdInUseForStore(typ)
    val batchSize = 100000
    val batches = (maxId / batchSize.toFloat).ceil.toInt

    (1 to batches)
      .map { batch => Future {
        val upper = batch * batchSize - 1
        val lower = upper - batchSize
        val extractor = typ match  {
          case Node => NodeExtractor(api, ctx)
          case Relationship => RelExtractor(api, ctx)
        }
        extractor(lower, upper)
    }}
  }

  /**
    * Generates the OkapiSchemaInfo entries for a given label combination / relationship type
    */
  private def getOkapiSchemaInfo(
    typ: EntityType,
    map: LabelPropertyKeyMap
  ): Stream[OkapiSchemaInfo] = map.data.asScala.flatMap {
    case (labelPointers, propertyMap) =>
      val labels = labelPointers.map(getLabelName(typ, _))

      if (propertyMap.isEmpty) {
        Seq(new OkapiSchemaInfo(typ.name, labels.toSeq.asJava, "", new util.ArrayList(0)))
      } else {
        propertyMap.asScala.map {
          case (propertyId, cypherTypes) =>
            new OkapiSchemaInfo(
              typ.name,
              labels.toSeq.asJava,
              getPropertyName(propertyId),
              cypherTypes.toList.asJava
            )
        }
      }
  }.asJavaCollection.stream()

  /**
    * Translates integers representing labels into the correct label name
    */
  private def getLabelName(typ: EntityType, id: Int): String = typ match {
    case Node => tx.token().nodeLabelName(id)
    case Relationship => tx.token().relationshipTypeName(id)
  }

  /**
    * Translates integers representing property names into the correct property name string
    */
  private def getPropertyName(id  : Int): String = tx.token().propertyKeyName(id)

  private def getHighestIdInUseForStore(typ: EntityType) = {
    val neoStores = api.getDependencyResolver.resolveDependency(classOf[RecordStorageEngine]).testAccessNeoStores
    val store = typ match {
      case Node => neoStores.getNodeStore
      case Relationship =>neoStores.getRelationshipStore
      case _ => throw new IllegalArgumentException("invalid type " + typ)
    }
    store.getHighId
  }
}

trait EntityType {
  def name: String
}

case object Node extends EntityType {
  override val name: String = "Node"
}

case object Relationship extends EntityType {
  override val name: String = "Relationship"
}

/**
  * Stores the gatheredd information about label, property, type combinations.
  * @note This implementation is mutable and does inplace updates
  */
class LabelPropertyKeyMap {
  val data: java.util.Map[Set[Int], java.util.Map[Int, mutable.Set[String]]] = new java.util.HashMap()

  /**
    * Given a label combination and a property cursor, this computes the new schema that results from including this
    * new information
    */
  def add(labels: Set[Int], propertyCursor: PropertyCursor): LabelPropertyKeyMap = {
    val isExistingLabel = data.containsKey(labels)
    data.putIfAbsent(labels, new java.util.HashMap())
    val labelData = data.get(labels)

    val remainingProperties = new util.HashSet(labelData.keySet())

    while(propertyCursor.next()) {
      val property = propertyCursor.propertyKey()
      remainingProperties.remove(property)

      val value = propertyCursor.propertyValue().asObject()

      val typ = CypherValue.get(value).map(_.cypherType) match {
        case Some(cypherType) => cypherType.name
        case None => value.getClass.getSimpleName
      }

      val existingTypes = labelData.putIfAbsent(property, mutable.Set.empty)
      val knownTypes = labelData.get(property)

      // we have seen this label combination before but not the property, so make the property nullable
      if(isExistingLabel && existingTypes == null) {
        knownTypes.add(CTNull.name)
      }

      knownTypes.add(typ)
    }

    // if remaining properties is not empty, then we have not seen these properties for the current element,
    // thus we have to make it nullable.
    remainingProperties.foreach(labelData.get(_).add(CTNull.toString))
    this
  }


  def ++(other: LabelPropertyKeyMap): LabelPropertyKeyMap = {
    other.data.keySet.foreach { labels =>
      if(data.containsKey(labels)) {
        val lData = data.get(labels)
        val rData = other.data.get(labels)

        val remainingProperties = new util.HashSet(lData.keySet())

        for(property <- rData.keySet()) {
          remainingProperties.remove(property)

          val existingTypes = lData.putIfAbsent(property, mutable.Set.empty)
          val knownTypes = lData.get(property)

          // we have seen this label combination before but not the property, so make the property nullable
          if(existingTypes == null) {
            knownTypes.add(CTNull.name)
          }

          knownTypes.addAll(rData.get(property))
        }

        remainingProperties.foreach(lData.get(_).add(CTNull.toString))

      } else {
        data.put(labels, other.data.get(labels))
      }
    }
    this
  }
}

trait WrappedCursor {
  def getNodeCursor: Option[NodeCursor]
  def getRelCursor: Option[RelationshipScanCursor]

  def close(): Unit
}

case class WrappedNodeCursor(cursor: NodeCursor) extends WrappedCursor {
  override def getNodeCursor: Option[NodeCursor] = Some(cursor)
  override def getRelCursor: Option[RelationshipScanCursor] = None
  override def close(): Unit = cursor.close()
}

case class WrappedRelationshipCursor(cursor: RelationshipScanCursor) extends WrappedCursor {
  override def getNodeCursor: Option[NodeCursor] = None
  override def getRelCursor: Option[RelationshipScanCursor] = Some(cursor)
  override def close(): Unit = cursor.close()
}

trait Extractor[T <: WrappedCursor] {
  def labelPropertyMap: LabelPropertyKeyMap

  def api: GraphDatabaseAPI
  def ctx: ThreadToStatementContextBridge

  def apply(lower: Long, upper: Long): LabelPropertyKeyMap = withTransaction {
    val kTx = ctx.getKernelTransactionBoundToThisThread(true)
    val cursors = kTx.cursors
    val read = kTx.dataRead

    val wrappedCursor = getCursor(cursors)
    val propertyCursor = cursors.allocatePropertyCursor()

    (lower to upper).foreach(iterate(_, wrappedCursor, propertyCursor, read))

    propertyCursor.close()
    wrappedCursor.close()

    labelPropertyMap
  }

  def getCursor(cursors: CursorFactory): T

  def iterate(i: Long, wrappedCursor: T, propertyCursor: PropertyCursor, read: Read): Unit

  /**
    * Runs the given function wrapped in a Neo4j transaction and returns the result
    *
    * @param function code that will be run inside the transaction
    * @tparam A return type of the function
    * @return
    */
  private def withTransaction[A](function: => A): A = {
    val tx = api.beginTx()
    val res = function
    tx.success()
    res
  }
}

case class NodeExtractor (
  override val api: GraphDatabaseAPI,
  override val ctx: ThreadToStatementContextBridge
) extends Extractor[WrappedNodeCursor] {

  override val labelPropertyMap: LabelPropertyKeyMap = new LabelPropertyKeyMap

  override def iterate(i: Long, wrappedCursor: WrappedNodeCursor, propertyCursor: PropertyCursor, read: Read): Unit = {
    val nodeCursor = wrappedCursor.cursor
    read.singleNode(i, nodeCursor)
    if(nodeCursor.next()) {
      nodeCursor.properties(propertyCursor)
      val labels = nodeCursor.labels().all()
      labelPropertyMap.add(labels.map(_.toInt).toSet, propertyCursor)
    }
  }

  override def getCursor(cursors: CursorFactory): WrappedNodeCursor = WrappedNodeCursor(cursors.allocateNodeCursor)
}

case class RelExtractor(
  override val api: GraphDatabaseAPI,
  override val ctx: ThreadToStatementContextBridge
) extends Extractor[WrappedRelationshipCursor] {

  override val labelPropertyMap: LabelPropertyKeyMap = new LabelPropertyKeyMap

  override def iterate(i: Long, wrappedCursor: WrappedRelationshipCursor, propertyCursor: PropertyCursor, read: Read): Unit = {
    val relCursor = wrappedCursor.cursor

    read.singleRelationship(i, relCursor)
    if(relCursor.next()) {
      relCursor.properties(propertyCursor)
      val relType = relCursor.`type`()
      labelPropertyMap.add(Set(relType), propertyCursor)
    }
  }

  override def getCursor(cursors: CursorFactory): WrappedRelationshipCursor =
    WrappedRelationshipCursor(cursors.allocateRelationshipScanCursor())
}