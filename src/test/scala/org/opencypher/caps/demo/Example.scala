/**
 * Copyright (c) 2016-2017 "Neo4j, Inc." [https://neo4j.com]
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
 */
package org.opencypher.caps.demo

import org.opencypher.caps.api.record.{NodeScan, RelationshipScan}
import org.opencypher.caps.api.spark.{CAPSGraph, CAPSSession}

object Example extends App {
  // Create session
  implicit val caps = CAPSSession.local()

  // Initial data model
  case class Person(id: Long, name: String)
  case class Friendship(id: Long, from: Long, to: Long, since: String)

  // Data mapped to DataFrames
  val personList = List(Person(0, "Alice"), Person(1, "Bob"), Person(2, "Carol"))
  val friendshipList= List(Friendship(0, 0, 1, "23/01/1987"), Friendship(1, 1, 2, "12/12/2009"))
  val personDf = caps.sparkSession.createDataFrame(personList)
  val friendshipDf = caps.sparkSession.createDataFrame(friendshipList)

  // Turn DataFrame into Node/Relationship scans
  val personScan = NodeScan.on("id") { builder =>
    builder.build.withImpliedLabel("Person").withPropertyKey("name")
  }.fromDf(personDf)
  val friendshipScan = RelationshipScan.on("id") {  builder =>
    builder.from("from").to("to").relType("FRIENDS").build.withPropertyKey("since")
  }.fromDf(friendshipDf)

  // Create CAPSGraph from scans
  val graph = CAPSGraph.create(personScan, friendshipScan)

  // Query graph with Cypher
  val result = graph.cypher("MATCH (a:Person)-[r:FRIENDS]->(b) RETURN a.name, b.name, r.since AS friendsSinceDate")
  result.records.print
}