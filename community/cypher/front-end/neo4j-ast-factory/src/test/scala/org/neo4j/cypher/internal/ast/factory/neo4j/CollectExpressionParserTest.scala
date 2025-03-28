/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.ast.factory.neo4j

import org.neo4j.cypher.internal.ast.AliasedReturnItem
import org.neo4j.cypher.internal.ast.CollectExpression
import org.neo4j.cypher.internal.ast.Match
import org.neo4j.cypher.internal.ast.Statement
import org.neo4j.cypher.internal.ast.UnaliasedReturnItem
import org.neo4j.cypher.internal.ast.UnionDistinct
import org.neo4j.cypher.internal.expressions.AllIterablePredicate
import org.neo4j.cypher.internal.expressions.CaseExpression
import org.neo4j.cypher.internal.expressions.ContainerIndex
import org.neo4j.cypher.internal.expressions.Equals
import org.neo4j.cypher.internal.expressions.EveryPath
import org.neo4j.cypher.internal.expressions.FilterScope
import org.neo4j.cypher.internal.expressions.NamedPatternPart
import org.neo4j.cypher.internal.expressions.Pattern
import org.neo4j.cypher.internal.expressions.SemanticDirection.INCOMING
import org.neo4j.cypher.internal.expressions.SemanticDirection.OUTGOING
import org.neo4j.cypher.internal.expressions.SignedDecimalIntegerLiteral
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.label_expressions.LabelExpression.Leaf
import org.neo4j.cypher.internal.util.InputPosition

class CollectExpressionParserTest extends JavaccParserAstTestBase[Statement] {

  implicit private val parser: JavaccRule[Statement] = JavaccRule.Statement

  test(
    """MATCH (m)
      |WHERE COLLECT { MATCH (m)-[r]->(p) RETURN m.test } = [1, 2, 5]
      |RETURN m""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          relationshipChain(
            nodePat(Some("m"), namePos = InputPosition(33, 2, 24), position = InputPosition(32, 2, 23)),
            relPat(Some("r"), namePos = InputPosition(37, 2, 28), position = InputPosition(35, 2, 26)),
            nodePat(Some("p"), namePos = InputPosition(42, 2, 33), position = InputPosition(41, 2, 32))
          )
        ),
        return_(returnItem(prop("m", "test"), "m.test"))
      )
    )(InputPosition(16, 2, 7), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("m")), Some(where(eq(collectExpression, listOfInt(1, 2, 5))))),
        return_(variableReturnItem("m"))
      )
    }
  }

  test(
    """MATCH (m)
      |WHERE COLLECT { MATCH (m)-[]->() RETURN m.test } = ["hello", "world"]
      |RETURN m""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          relationshipChain(
            nodePat(Some("m"), namePos = InputPosition(33, 2, 24), position = InputPosition(32, 2, 23)),
            relPat(position = InputPosition(35, 2, 26)),
            nodePat(None, position = InputPosition(40, 2, 31))
          )
        ),
        return_(returnItem(prop("m", "test"), "m.test"))
      )
    )(InputPosition(16, 2, 7), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("m")), Some(where(eq(collectExpression, listOfString("hello", "world"))))),
        return_(variableReturnItem("m"))
      )
    }
  }

  test(
    """MATCH (m)
      |WHERE COLLECT { MATCH (m) RETURN m.test } = [1, "test", null]
      |RETURN m""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          nodePat(Some("m"), namePos = InputPosition(33, 2, 24), position = InputPosition(32, 2, 23))
        ),
        return_(returnItem(prop("m", "test"), "m.test"))
      )
    )(InputPosition(16, 2, 7), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(
          nodePat(name = Some("m")),
          Some(where(eq(collectExpression, listOf(literalInt(1), literalString("test"), nullLiteral))))
        ),
        return_(variableReturnItem("m"))
      )
    }
  }

  test(
    """MATCH (m)
      |WHERE COLLECT { MATCH (m) WHERE m.prop = 3 RETURN m.prop } = [1]
      |RETURN m""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          nodePat(Some("m"), namePos = InputPosition(33, 2, 24), position = InputPosition(32, 2, 23)),
          Some(where(propEquality("m", "prop", 3)))
        ),
        return_(returnItem(prop("m", "prop"), "m.prop"))
      )
    )(InputPosition(16, 2, 7), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("m")), Some(where(eq(collectExpression, listOfInt(1))))),
        return_(variableReturnItem("m"))
      )
    }
  }

  // COLLECT in a RETURN statement
  test(
    """MATCH (m)
      |RETURN COLLECT { MATCH (m)-[]->() RETURN m }""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          relationshipChain(
            nodePat(Some("m"), namePos = InputPosition(34, 2, 25), position = InputPosition(33, 2, 24)),
            relPat(position = InputPosition(36, 2, 27)),
            nodePat(None, position = InputPosition(41, 2, 32))
          )
        ),
        return_(variableReturnItem("m"))
      )
    )(InputPosition(17, 2, 8), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("m"))),
        return_(returnItem(collectExpression, "COLLECT { MATCH (m)-[]->() RETURN m }", InputPosition(17, 2, 8)))
      )
    }
  }

  // COUNT in a SET statement
  test(
    """MATCH (m)
      |SET m.listItems = COLLECT { MATCH (a) RETURN a.prop }
    """.stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          nodePat(Some("a"), namePos = InputPosition(45, 2, 36), position = InputPosition(44, 2, 35))
        ),
        return_(returnItem(prop("a", "prop"), "a.prop"))
      )
    )(InputPosition(28, 2, 19), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("m"))),
        set_(Seq(setPropertyItem("m", "listItems", collectExpression)))
      )
    }
  }

  // COLLECT in a WHEN statement
  test(
    """MATCH (m)
      |RETURN CASE WHEN COLLECT { MATCH (m)-[]->() RETURN m.prop } = [1, 2] THEN "hasProperty" ELSE "other" END
    """.stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          relationshipChain(
            nodePat(Some("m"), namePos = InputPosition(44, 2, 35), position = InputPosition(43, 2, 34)),
            relPat(position = InputPosition(46, 2, 37)),
            nodePat(None, position = InputPosition(51, 2, 42))
          )
        ),
        return_(returnItem(prop("m", "prop"), "m.prop"))
      )
    )(InputPosition(27, 2, 18), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("m"))),
        return_(UnaliasedReturnItem(
          CaseExpression(
            None,
            List((eq(collectExpression, listOfInt(1, 2)), literal("hasProperty"))),
            Some(literal("other"))
          )(
            pos
          ),
          "CASE WHEN COLLECT { MATCH (m)-[]->() RETURN m.prop } = [1, 2] THEN \"hasProperty\" ELSE \"other\" END"
        )(pos))
      )
    }
  }

  // COLLECT in a WITH statement
  test("WITH COLLECT { MATCH (m)-[]->() RETURN m.prop } AS result RETURN result") {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          relationshipChain(
            nodePat(Some("m"), namePos = InputPosition(22, 1, 23), position = InputPosition(21, 1, 22)),
            relPat(position = InputPosition(24, 1, 25)),
            nodePat(None, position = InputPosition(29, 1, 30))
          )
        ),
        return_(returnItem(prop("m", "prop"), "m.prop"))
      )
    )(InputPosition(5, 1, 6), None, None)

    givesIncludingPositions {
      singleQuery(
        with_(AliasedReturnItem(collectExpression, Variable("result")(pos))(pos, isAutoAliased = false)),
        return_(UnaliasedReturnItem(Variable("result")(pos), "result")(pos))
      )
    }
  }

  test("MATCH (a) WHERE COLLECT{ MATCH (a: Label) RETURN a.prop }[0] < 9 RETURN a") {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          nodePat(
            Some("a"),
            Some(labelLeaf("Label", InputPosition(35, 1, 36))),
            namePos = InputPosition(32, 1, 33),
            position = InputPosition(31, 1, 32)
          )
        ),
        return_(returnItem(prop("a", "prop"), "a.prop"))
      )
    )(InputPosition(16, 1, 17), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(
          nodePat(name = Some("a")),
          Some(where(lt(ContainerIndex(collectExpression, literal(0))(pos), literal(9))))
        ),
        return_(variableReturnItem("a"))
      )
    }
  }

  test("MATCH (a) RETURN COLLECT { MATCH (a) }") {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          nodePat(Some("a"), namePos = InputPosition(34, 1, 35), position = InputPosition(33, 1, 34))
        )
      )
    )(InputPosition(17, 1, 18), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("a")), None),
        return_(returnItem(collectExpression, "COLLECT { MATCH (a) }"))
      )
    }
  }

  test(
    """MATCH (a), (b)
      |WHERE COLLECT { MATCH (a)-[:FOLLOWS]->(b), (a)<-[:FOLLOWS]-(b) RETURN a.prop }[0] > 6
      |RETURN a, b
      |""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          Seq(
            relationshipChain(
              nodePat(Some("a"), namePos = InputPosition(38, 2, 24), position = InputPosition(37, 2, 23)),
              relPat(None, Some(Leaf(relTypeName("FOLLOWS"))), None, None, None, OUTGOING),
              nodePat(Some("b"), namePos = InputPosition(54, 2, 40), position = InputPosition(53, 2, 39))
            ),
            relationshipChain(
              nodePat(Some("a"), namePos = InputPosition(59, 2, 45), position = InputPosition(58, 2, 44)),
              relPat(None, Some(Leaf(relTypeName("FOLLOWS"))), None, None, None, INCOMING),
              nodePat(Some("b"), namePos = InputPosition(75, 2, 61), position = InputPosition(74, 2, 60))
            )
          ),
          None
        ),
        return_(returnItem(prop("a", "prop"), "a.prop"))
      )
    )(InputPosition(21, 2, 7), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(
          Seq(
            nodePat(name = Some("a")),
            nodePat(name = Some("b"))
          ),
          Some(where(gt(ContainerIndex(collectExpression, literal(0))(pos), literal(6))))
        ),
        return_(variableReturnItem("a"), variableReturnItem("b"))
      )
    }
  }

  test("MATCH (a) WHERE COLLECT { MATCH pt = (a)-[]->(b) RETURN nodes(pt)[0].prop }[1] >= 5 RETURN a") {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        Match(
          optional = false,
          Pattern(Seq(
            NamedPatternPart(
              varFor("pt"),
              EveryPath(
                relationshipChain(
                  nodePat(Some("a"), namePos = InputPosition(38, 1, 39), position = InputPosition(37, 1, 38)),
                  relPat(position = InputPosition(40, 1, 41)),
                  nodePat(Some("b"), namePos = InputPosition(46, 1, 47), position = InputPosition(45, 1, 46))
                )
              )
            )(InputPosition(32, 1, 33))
          ))(InputPosition(32, 1, 33)),
          Seq.empty,
          None
        )(pos),
        return_(returnItem(
          prop(ContainerIndex(function("nodes", varFor("pt")), literal(0))(pos), "prop"),
          "nodes(pt)[0].prop"
        ))
      )
    )(InputPosition(16, 1, 17), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(
          nodePat(name = Some("a")),
          Some(where(gte(ContainerIndex(collectExpression, literal(1))(pos), literal(5))))
        ),
        return_(variableReturnItem("a"))
      )
    }
  }

  test(
    """MATCH (m)
      |WHERE COLLECT { MATCH (m)-[r]->(p) RETURN p }[2] <= 2
      |RETURN m""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          relationshipChain(
            nodePat(Some("m"), namePos = InputPosition(33, 2, 24), position = InputPosition(32, 2, 23)),
            relPat(Some("r"), namePos = InputPosition(37, 2, 28), position = InputPosition(35, 2, 26)),
            nodePat(Some("p"), namePos = InputPosition(42, 2, 33), position = InputPosition(41, 2, 32))
          )
        ),
        return_(variableReturnItem("p"))
      )
    )(InputPosition(16, 2, 7), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(
          nodePat(name = Some("m")),
          Some(where(lte(ContainerIndex(collectExpression, literal(2))(pos), literal(2))))
        ),
        return_(variableReturnItem("m"))
      )
    }
  }

  test(
    """MATCH (m)
      |WHERE COLLECT { MATCH (m) RETURN m.prop AS a UNION MATCH (p) RETURN p.prop AS a } = [1, 2, 3]
      |RETURN m""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      UnionDistinct(
        singleQuery(
          match_(nodePat(name = Some("m"), namePos = InputPosition(33, 2, 24), position = InputPosition(32, 2, 23))),
          return_(aliasedReturnItem(prop("m", "prop"), "a"))
        ),
        singleQuery(
          match_(nodePat(name = Some("p"), namePos = InputPosition(68, 2, 59), position = InputPosition(67, 2, 58))),
          return_(aliasedReturnItem(prop("p", "prop"), "a"))
        )
      )(InputPosition(55, 2, 46))
    )(InputPosition(16, 2, 7), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("m")), Some(where(eq(collectExpression, listOfInt(1, 2, 3))))),
        return_(variableReturnItem("m"))
      )
    }
  }

  test(
    """MATCH (m)
      |WHERE COLLECT { CREATE (n) } = []
      |RETURN m""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        create(nodePat(name = Some("n"), namePos = InputPosition(34, 2, 25), position = InputPosition(33, 2, 24))),
        InputPosition(24, 2, 15)
      )
    )(InputPosition(16, 2, 7), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("m")), Some(where(eq(collectExpression, listOf())))),
        return_(variableReturnItem("m"))
      )
    }
  }

  test(
    """MATCH (m)
      |WHERE COLLECT { MATCH (n) WHERE all(i in n.prop WHERE i = 4) RETURN n } = []
      |RETURN m""".stripMargin
  ) {
    val collectExpression: CollectExpression = CollectExpression(
      singleQuery(
        match_(
          nodePat(name = Some("n"), namePos = InputPosition(33, 2, 24), position = InputPosition(32, 2, 23)),
          Some(
            where(
              AllIterablePredicate(
                FilterScope(
                  varFor("i"),
                  Some(
                    Equals(varFor("i"), SignedDecimalIntegerLiteral("4")(pos))(pos)
                  )
                )(pos),
                prop("n", "prop")
              )(pos)
            )
          )
        ),
        return_(variableReturnItem("n"))
      )
    )(InputPosition(16, 2, 7), None, None)

    givesIncludingPositions {
      singleQuery(
        match_(nodePat(name = Some("m")), Some(where(eq(collectExpression, listOf())))),
        return_(variableReturnItem("m"))
      )
    }
  }

  test(
    """MATCH (m)
      |WHERE COLLECT { MATCH (b) RETURN b WHERE true } = [1, 2, 3]
      |RETURN m""".stripMargin
  ) {
    failsToParse
  }
}
