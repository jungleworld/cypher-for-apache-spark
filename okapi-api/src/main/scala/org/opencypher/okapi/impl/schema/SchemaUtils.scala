/*
 * Copyright (c) 2016-2018 "Neo4j, Inc." [https://neo4j.com]
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
package org.opencypher.okapi.impl.schema

import org.opencypher.okapi.api.schema.Schema

object SchemaUtils {

  implicit class RichSchema(schema: Schema) {
  // TODO: Document
    def foldAndProduce[A](
      zero: Map[String, A])(bound: (A, Set[String], String) => A, fresh: (Set[String], String) => A): Map[String, A] = {
      schema.labelPropertyMap.labelCombinations.foldLeft(zero) {
        case (map, labelCombos) =>
          labelCombos.foldLeft(map) {
            case (innerMap, label) =>
              innerMap.get(label) match {
                case Some(a) =>
                  innerMap.updated(label, bound(a, labelCombos, label))
                case None =>
                  innerMap.updated(label, fresh(labelCombos, label))
              }
          }
      }
    }
  }

}
