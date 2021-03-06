/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.joins

import org.apache.spark.sql.catalyst.planning.ExtractEquiJoinKeys
import org.apache.spark.sql.catalyst.plans.logical.Join
import org.apache.spark.sql.test.SQLTestUtils
import org.apache.spark.sql.types.{IntegerType, DoubleType, StructType}
import org.apache.spark.sql.{SQLConf, DataFrame, Row}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.execution.{EnsureRequirements, joins, SparkPlan, SparkPlanTest}

class OuterJoinSuite extends SparkPlanTest with SQLTestUtils {

  private def testOuterJoin(
      testName: String,
      leftRows: DataFrame,
      rightRows: DataFrame,
      joinType: JoinType,
      condition: Expression,
      expectedAnswer: Seq[Product]): Unit = {
    val join = Join(leftRows.logicalPlan, rightRows.logicalPlan, Inner, Some(condition))
    ExtractEquiJoinKeys.unapply(join).foreach {
      case (_, leftKeys, rightKeys, boundCondition, leftChild, rightChild) =>
        test(s"$testName using ShuffledHashOuterJoin") {
          withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
            checkAnswer2(leftRows, rightRows, (left: SparkPlan, right: SparkPlan) =>
              EnsureRequirements(sqlContext).apply(
                ShuffledHashOuterJoin(leftKeys, rightKeys, joinType, boundCondition, left, right)),
              expectedAnswer.map(Row.fromTuple),
              sortAnswers = true)
          }
        }

        if (joinType != FullOuter) {
          test(s"$testName using BroadcastHashOuterJoin") {
            withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
              checkAnswer2(leftRows, rightRows, (left: SparkPlan, right: SparkPlan) =>
                BroadcastHashOuterJoin(leftKeys, rightKeys, joinType, boundCondition, left, right),
                expectedAnswer.map(Row.fromTuple),
                sortAnswers = true)
            }
          }

          test(s"$testName using SortMergeOuterJoin") {
            withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
              checkAnswer2(leftRows, rightRows, (left: SparkPlan, right: SparkPlan) =>
                EnsureRequirements(sqlContext).apply(
                  SortMergeOuterJoin(leftKeys, rightKeys, joinType, boundCondition, left, right)),
                expectedAnswer.map(Row.fromTuple),
                sortAnswers = false)
            }
          }
        }
    }

    test(s"$testName using BroadcastNestedLoopJoin (build=left)") {
      withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
        checkAnswer2(leftRows, rightRows, (left: SparkPlan, right: SparkPlan) =>
          joins.BroadcastNestedLoopJoin(left, right, joins.BuildLeft, joinType, Some(condition)),
          expectedAnswer.map(Row.fromTuple),
          sortAnswers = true)
      }
    }

    test(s"$testName using BroadcastNestedLoopJoin (build=right)") {
      withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
        checkAnswer2(leftRows, rightRows, (left: SparkPlan, right: SparkPlan) =>
          joins.BroadcastNestedLoopJoin(left, right, joins.BuildRight, joinType, Some(condition)),
          expectedAnswer.map(Row.fromTuple),
          sortAnswers = true)
      }
    }
  }

  val left = sqlContext.createDataFrame(sqlContext.sparkContext.parallelize(Seq(
    Row(1, 2.0),
    Row(2, 100.0),
    Row(2, 1.0), // This row is duplicated to ensure that we will have multiple buffered matches
    Row(2, 1.0),
    Row(3, 3.0),
    Row(5, 1.0),
    Row(6, 6.0),
    Row(null, null)
  )), new StructType().add("a", IntegerType).add("b", DoubleType))

  val right = sqlContext.createDataFrame(sqlContext.sparkContext.parallelize(Seq(
    Row(0, 0.0),
    Row(2, 3.0), // This row is duplicated to ensure that we will have multiple buffered matches
    Row(2, -1.0),
    Row(2, -1.0),
    Row(2, 3.0),
    Row(3, 2.0),
    Row(4, 1.0),
    Row(5, 3.0),
    Row(7, 7.0),
    Row(null, null)
  )), new StructType().add("c", IntegerType).add("d", DoubleType))

  val condition = {
    And(
      (left.col("a") === right.col("c")).expr,
      LessThan(left.col("b").expr, right.col("d").expr))
  }

  // --- Basic outer joins ------------------------------------------------------------------------

  testOuterJoin(
    "basic left outer join",
    left,
    right,
    LeftOuter,
    condition,
    Seq(
      (null, null, null, null),
      (1, 2.0, null, null),
      (2, 100.0, null, null),
      (2, 1.0, 2, 3.0),
      (2, 1.0, 2, 3.0),
      (2, 1.0, 2, 3.0),
      (2, 1.0, 2, 3.0),
      (3, 3.0, null, null),
      (5, 1.0, 5, 3.0),
      (6, 6.0, null, null)
    )
  )

  testOuterJoin(
    "basic right outer join",
    left,
    right,
    RightOuter,
    condition,
    Seq(
      (null, null, null, null),
      (null, null, 0, 0.0),
      (2, 1.0, 2, 3.0),
      (2, 1.0, 2, 3.0),
      (null, null, 2, -1.0),
      (null, null, 2, -1.0),
      (2, 1.0, 2, 3.0),
      (2, 1.0, 2, 3.0),
      (null, null, 3, 2.0),
      (null, null, 4, 1.0),
      (5, 1.0, 5, 3.0),
      (null, null, 7, 7.0)
    )
  )

  testOuterJoin(
    "basic full outer join",
    left,
    right,
    FullOuter,
    condition,
    Seq(
      (1, 2.0, null, null),
      (null, null, 2, -1.0),
      (null, null, 2, -1.0),
      (2, 100.0, null, null),
      (2, 1.0, 2, 3.0),
      (2, 1.0, 2, 3.0),
      (2, 1.0, 2, 3.0),
      (2, 1.0, 2, 3.0),
      (3, 3.0, null, null),
      (5, 1.0, 5, 3.0),
      (6, 6.0, null, null),
      (null, null, 0, 0.0),
      (null, null, 3, 2.0),
      (null, null, 4, 1.0),
      (null, null, 7, 7.0),
      (null, null, null, null),
      (null, null, null, null)
    )
  )

  // --- Both inputs empty ------------------------------------------------------------------------

  testOuterJoin(
    "left outer join with both inputs empty",
    left.filter("false"),
    right.filter("false"),
    LeftOuter,
    condition,
    Seq.empty
  )

  testOuterJoin(
    "right outer join with both inputs empty",
    left.filter("false"),
    right.filter("false"),
    RightOuter,
    condition,
    Seq.empty
  )

  testOuterJoin(
    "full outer join with both inputs empty",
    left.filter("false"),
    right.filter("false"),
    FullOuter,
    condition,
    Seq.empty
  )
}
