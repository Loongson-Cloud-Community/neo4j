/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.cypher.internal.ast.semantics.functions

import org.neo4j.cypher.internal.util.symbols.CTAny
import org.neo4j.cypher.internal.util.symbols.CTDate
import org.neo4j.cypher.internal.util.symbols.CTFloat
import org.neo4j.cypher.internal.util.symbols.CTInteger
import org.neo4j.cypher.internal.util.symbols.CTList
import org.neo4j.cypher.internal.util.symbols.CTNode
import org.neo4j.cypher.internal.util.symbols.CTPoint
import org.neo4j.cypher.internal.util.symbols.CTString

class ToFloatListTest extends FunctionTestBase("toFloatList") {

  test("shouldAcceptCorrectTypes") {
    testValidTypes(CTList(CTAny))(CTList(CTFloat))
    testValidTypes(CTList(CTString))(CTList(CTFloat))
    testValidTypes(CTList(CTFloat))(CTList(CTFloat))
    testValidTypes(CTList(CTInteger))(CTList(CTFloat))
    testValidTypes(CTList(CTPoint))(CTList(CTFloat))
  }

  test("shouldFailTypeCheckForIncompatibleArguments") {
    testInvalidApplication(CTNode)(
      "Type mismatch: expected List<T> but was Node"
    )

    testInvalidApplication(CTDate)(
      "Type mismatch: expected List<T> but was Date"
    )

    testInvalidApplication(CTString)(
      "Type mismatch: expected List<T> but was String"
    )
  }

  test("shouldFailIfWrongNumberOfArguments") {
    testInvalidApplication()(
      "Insufficient parameters for function 'toFloatList'"
    )
    testInvalidApplication(CTString, CTString)(
      "Too many parameters for function 'toFloatList'"
    )
  }
}
