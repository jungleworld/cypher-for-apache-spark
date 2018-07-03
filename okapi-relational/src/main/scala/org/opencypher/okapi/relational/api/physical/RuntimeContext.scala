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
package org.opencypher.okapi.relational.api.physical

import org.opencypher.okapi.api.graph.{CypherSession, QualifiedGraphName}
import org.opencypher.okapi.api.value.CypherValue.CypherMap
import org.opencypher.okapi.relational.api.graph.RelationalCypherGraph
import org.opencypher.okapi.relational.api.table.{FlatRelationalTable, RelationalCypherRecords}
import org.opencypher.okapi.relational.impl.operators.RelationalOperator

import scala.collection.mutable

/**
  * Represents a back-end specific runtime context that is being used by [[RelationalOperator]] implementations.
  *
  * @tparam K backend-specific cypher records
  * @tparam P backend-specific property graph
  */
trait RuntimeContext[
O <: FlatRelationalTable[O],
K <: RelationalOperator[O, K, A, P, I],
A <: RelationalCypherRecords[O], 
P <: RelationalCypherGraph[O],
I <: RuntimeContext[O, K, A, P, I]] {

  /**
    * Returns the graph referenced by the given QualifiedGraphName.
    *
    * @return back-end specific property graph
    */
  def resolveGraph(qgn: QualifiedGraphName): P

  /**
    * Query parameters
    *
    * @return query parameters
    */
  def parameters: CypherMap

  /**
    * The session the query runs on.
    *
    * @return cypher session
    */
  def session: CypherSession

  /**
    * Operator cache
    */
  var cache: mutable.Map[K, O]

}
