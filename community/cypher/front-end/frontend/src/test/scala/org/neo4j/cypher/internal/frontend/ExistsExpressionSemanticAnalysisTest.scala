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
package org.neo4j.cypher.internal.frontend

import org.neo4j.cypher.internal.ast.semantics.SemanticError
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class ExistsExpressionSemanticAnalysisTest
    extends CypherFunSuite
    with NameBasedSemanticAnalysisTestSuite {

  test("""MATCH (a)
         |RETURN EXISTS { CREATE (b) }
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "An Exists Expression cannot contain any updates",
        InputPosition(17, 2, 8)
      )
    )
  }

  test("""MATCH (m)
         |WHERE EXISTS { OPTIONAL MATCH (a)-[r]->(b) }
         |RETURN m
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe empty
  }

  test("""MATCH (a)
         |WHERE EXISTS {
         |  MATCH (a)
         |  RETURN *
         |}
         |RETURN a
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe empty
  }

  test("""MATCH (m)
         |WHERE EXISTS { MATCH (a:A)-[r]->(b) USING SCAN a:A }
         |RETURN m
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe empty
  }

  test("""MATCH (a)
         |RETURN EXISTS { SET a.name = 1 }
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "An Exists Expression cannot contain any updates",
        InputPosition(17, 2, 8)
      )
    )
  }

  test("""MATCH (a)
         |RETURN EXISTS { MATCH (b) WHERE b.a = a.a DETACH DELETE b }
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "An Exists Expression cannot contain any updates",
        InputPosition(17, 2, 8)
      )
    )
  }

  test("""MATCH (a)
         |RETURN EXISTS { MATCH (b) MERGE (b)-[:FOLLOWS]->(:Person) }
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "An Exists Expression cannot contain any updates",
        InputPosition(17, 2, 8)
      )
    )
  }

  test("""MATCH (a)
         |RETURN EXISTS { CALL db.labels() }
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe empty
  }

  test("""MATCH (a)
         |RETURN EXISTS {
         |   MATCH (a)-[:KNOWS]->(b)
         |   RETURN b.name as name
         |   UNION ALL
         |   MATCH (a)-[:LOVES]->(b)
         |   RETURN b.name as name
         |}""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe empty
  }

  test("""MATCH (a)
         |RETURN EXISTS { MATCH (m)-[r]->(p), (a)-[r2]-(c) }
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe empty
  }

  test("""MATCH (a)
         |RETURN EXISTS { (a)-->(b) WHERE b.prop = 5  }
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe empty
  }

  test("""WITH 5 as aNum
         |MATCH (a)
         |RETURN EXISTS {
         |  WITH 6 as aNum
         |  MATCH (a)-->(b) WHERE b.prop = aNum
         |  RETURN a
         |}
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "The variable `aNum` is shadowing a variable with the same name from the outer scope and needs to be renamed",
        InputPosition(53, 4, 13)
      )
    )
  }

  test("""WITH 5 as aNum
         |MATCH (a)
         |RETURN EXISTS {
         |  MATCH (a)-->(b) WHERE b.prop = aNum
         |  WITH 6 as aNum
         |  MATCH (b)-->(c) WHERE c.prop = aNum
         |  RETURN a
         |}
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "The variable `aNum` is shadowing a variable with the same name from the outer scope and needs to be renamed",
        InputPosition(91, 5, 13)
      )
    )
  }

  test("""MATCH (a)
         |RETURN EXISTS {
         |  MATCH (a)-->(b)
         |  WITH b as a
         |  MATCH (b)-->(c)
         |  RETURN a
         |}
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "The variable `a` is shadowing a variable with the same name from the outer scope and needs to be renamed",
        InputPosition(56, 4, 13)
      )
    )
  }

  test("""MATCH (a)
         |RETURN EXISTS {
         |  MATCH (a)-->(b)
         |  WITH b as c
         |  MATCH (c)-->(d)
         |  RETURN a
         |}
         |""".stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe Set.empty
  }

  test("""MATCH (person:Person)
         |WHERE EXISTS {
         |    RETURN CASE
         |       WHEN true THEN 1
         |       ELSE 2
         |    END
         |}
         |RETURN person.name
     """.stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe Set.empty
  }

  test("""MATCH (person:Person)
         |WHERE EXISTS {
         |    MATCH (n)
         |    UNION
         |    MATCH (m)
         |}
         |RETURN person.name
     """.stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe Set.empty
  }

  test("""MATCH (person:Person)
         |WHERE EXISTS {
         |    MATCH (n)
         |    UNION ALL
         |    MATCH (m)
         |}
         |RETURN person.name
     """.stripMargin) {
    runSemanticAnalysis().errors.toSet shouldBe Set.empty
  }

  test("""MATCH (person:Person)
         |WHERE EXISTS {
         |    MATCH (n)
         |    RETURN n.prop
         |    UNION ALL
         |    MATCH (m)
         |}
         |RETURN person.name
     """.stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "All sub queries in an UNION must have the same return column names",
        InputPosition(73, 5, 5)
      )
    )
  }

  test("""MATCH (person:Person)
         |WHERE EXISTS {
         |    MATCH (n)
         |    UNION ALL
         |    MATCH (m)
         |    RETURN m
         |}
         |RETURN person.name
     """.stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "All sub queries in an UNION must have the same return column names",
        InputPosition(55, 4, 5)
      )
    )
  }

  test("""MATCH (person:Person)
         |WHERE EXISTS {
         |    MATCH (n)
         |    RETURN n
         |    UNION
         |    MATCH (m)
         |}
         |RETURN person.name
     """.stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "All sub queries in an UNION must have the same return column names",
        InputPosition(68, 5, 5)
      )
    )
  }

  test("""MATCH (person:Person)
         |WHERE EXISTS {
         |    MATCH (n)
         |    UNION
         |    MATCH (m)
         |    RETURN m.prop
         |}
         |RETURN person.name
     """.stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "All sub queries in an UNION must have the same return column names",
        InputPosition(55, 4, 5)
      )
    )
  }

  test("""MATCH (person:Person)
         |WHERE EXISTS {
         |    MATCH (n)
         |    UNION
         |    MATCH (m)
         |    RETURN m
         |    UNION
         |    MATCH (l)
         |}
         |RETURN person.name
     """.stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "All sub queries in an UNION must have the same return column names",
        InputPosition(55, 4, 5)
      )
    )
  }

  test("""MATCH (person:Person)
         |WHERE EXISTS {
         |    MATCH (n)
         |    RETURN n
         |    UNION
         |    MATCH (m)
         |    RETURN m
         |    UNION
         |    MATCH (l)
         |    RETURN l
         |}
         |RETURN person.name
     """.stripMargin) {
    runSemanticAnalysis().errors.toSet shouldEqual Set(
      SemanticError(
        "All sub queries in an UNION must have the same return column names",
        InputPosition(68, 5, 5)
      ),
      SemanticError(
        "All sub queries in an UNION must have the same return column names",
        InputPosition(105, 8, 5)
      )
    )
  }
}
