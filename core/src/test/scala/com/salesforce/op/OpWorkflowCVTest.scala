/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op

import java.nio.file.Paths

import com.salesforce.app.schema.PassengerDataAll
import com.salesforce.op.evaluators._
import com.salesforce.op.features._
import com.salesforce.op.features.types._
import com.salesforce.op.readers.DataFrameFieldNames._
import com.salesforce.op.readers._
import com.salesforce.op.stages.base.binary.BinaryTransformer
import com.salesforce.op.stages.impl.classification.ClassificationModelsToTry._
import com.salesforce.op.stages.impl.classification._
import com.salesforce.op.stages.impl.preparators.SanityChecker
import com.salesforce.op.stages.impl.regression.{LossType, RegressionModelSelector, RegressionModelsToTry}
import com.salesforce.op.stages.impl.selector.ModelSelectorBase
import com.salesforce.op.stages.impl.tuning._
import com.salesforce.op.test.PassengerSparkFixtureTest
import org.apache.spark.ml.PipelineStage
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.sql.DataFrame
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

@RunWith(classOf[JUnitRunner])
class OpWorkflowCVTest extends FlatSpec with PassengerSparkFixtureTest {

  val log = LoggerFactory.getLogger(this.getClass)

  trait PassenserCSVforCV {
    val simplePassengerForCV = DataReaders.Simple.csv[PassengerDataAll](
      path = Some(Paths.get(testDataDir, "PassengerDataAll.csv").toString),
      schema = PassengerDataAll.getClassSchema.toString,
      key = _.getPassengerId.toString
    )
    val age = FeatureBuilder.Real[PassengerDataAll].extract(_.getAge.toReal).asPredictor
    val sex = FeatureBuilder.PickList[PassengerDataAll].extract(_.getSex.toPickList).asPredictor
    val fair = FeatureBuilder.Real[PassengerDataAll].extract(p => Option(p.getFare).map(_.toDouble).toReal).asPredictor
    val pClass = FeatureBuilder.PickList[PassengerDataAll].extract(_.getPclass.toString.toPickList).asPredictor
    val cabin = FeatureBuilder.PickList[PassengerDataAll].extract(_.getCabin.toPickList).asPredictor
    val survived = FeatureBuilder.Binary[PassengerDataAll].extract(p => p.getSurvived.intValue.toBinary).asResponse
    val survivedPred = FeatureBuilder.Binary[PassengerDataAll].extract(p => p.getSurvived.intValue.toBinary).asPredictor
    val survivedNum = survived.occurs()
  }

  Spec[OpWorkflow] should
    "return a binary classification model that runs cv at the workflow level" in new PassenserCSVforCV {
    val fv = Seq(age, sex, fair, pClass, cabin).transmogrify()
    val checked = survivedNum.sanityCheck(fv)

    val (pred1, _, prob1) = new BinaryClassificationModelSelector(
      validator = new OpCrossValidation(evaluator = Evaluators.BinaryClassification.auPR(), numFolds = 2, seed = 0L),
      splitter = Option(DataBalancer(sampleFraction = 0.01, reserveTestFraction = 0.2, seed = 0L)),
      evaluators = Seq(new OpBinaryClassificationEvaluator)
    ).setModelsToTry(LogisticRegression, RandomForest)
      .setLogisticRegressionRegParam(10000)
      .setLogisticRegressionElasticNetParam(0.01, 0.5)
      .setRandomForestMaxBins(10)
      .setInput(survivedNum, checked)
      .getOutput()

    val wf1 = new OpWorkflow().withWorkflowCV.setResultFeatures(pred1, prob1)
    wf1.isWorkflowCV shouldBe true
    val model1 = wf1.setReader(simplePassengerForCV).train()
    val data1 = model1.score(keepRawFeatures = false, keepIntermediateFeatures = false)

    val (pred2, _, prob2) = new BinaryClassificationModelSelector(
      validator = new OpCrossValidation(
        evaluator = Evaluators.BinaryClassification.auPR(), numFolds = 2, seed = 0L, parallelism = 4
      ),
      splitter = Option(DataBalancer(sampleFraction = 0.01, reserveTestFraction = 0.2, seed = 0L)),
      evaluators = Seq(new OpBinaryClassificationEvaluator)
    ).setModelsToTry(LogisticRegression, RandomForest)
      .setLogisticRegressionRegParam(10000)
      .setLogisticRegressionElasticNetParam(0.01, 0.5)
      .setRandomForestMaxBins(10)
      .setInput(survivedNum, checked)
      .getOutput()

    val wf2 = new OpWorkflow().setResultFeatures(pred2, prob2)
    wf2.isWorkflowCV shouldBe false
    val model2 = wf2.setReader(simplePassengerForCV).train()
    val data2 = model2.score(keepRawFeatures = false, keepIntermediateFeatures = false)

    compare(data1, data2, pred1, pred2, Some(prob1), Some(prob2))

    val summary = model1.summary()
    summary should include (classOf[SanityChecker].getSimpleName)
    summary should include (""""HoldoutEvaluation" : [ "com.salesforce.op.evaluators.MultiMetrics"""")
    summary should include ("TrainEvaluation")
  }

  it should "return a multi classification model that runs ts at the workflow level" in new PassenserCSVforCV {
    val fv = Seq(age, sex, fair, pClass, cabin).transmogrify()
    val checked = survivedNum.sanityCheck(fv)

    val (pred1, _, prob1) = new MultiClassificationModelSelector(
      validator = new OpTrainValidationSplit(evaluator = Evaluators.MultiClassification.error()),
      splitter = Option(DataCutter(reserveTestFraction = 0.2, seed = 0L)),
      evaluators = Seq(new OpMultiClassificationEvaluator())
    ).setModelsToTry(LogisticRegression, DecisionTree)
      .setLogisticRegressionMaxIter(10)
      .setLogisticRegressionRegParam(0.1)
      .setDecisionTreeMaxDepth(5, 10)
      .setDecisionTreeMinInfoGain(100000)
      .setInput(survivedNum, checked)
      .getOutput()

    val wf1 = new OpWorkflow().withWorkflowCV.setResultFeatures(pred1, prob1)
    wf1.isWorkflowCV shouldBe true
    val model1 = wf1.setReader(simplePassengerForCV).train()
    val data1 = model1.score(keepRawFeatures = false, keepIntermediateFeatures = false)


    val (pred2, _, prob2) = new MultiClassificationModelSelector(
      validator = new OpTrainValidationSplit(
        evaluator = Evaluators.MultiClassification.error(), parallelism = 4
      ),
      splitter = Option(DataCutter(reserveTestFraction = 0.2, seed = 0L)),
      evaluators = Seq(new OpMultiClassificationEvaluator())
    ).setModelsToTry(LogisticRegression, DecisionTree)
      .setLogisticRegressionMaxIter(10)
      .setLogisticRegressionRegParam(0.1)
      .setDecisionTreeMaxDepth(5, 10)
      .setDecisionTreeMinInfoGain(100000)
      .setInput(survivedNum, checked)
      .getOutput()

    val wf2 = new OpWorkflow().setResultFeatures(pred2, prob2)
    wf2.isWorkflowCV shouldBe false
    val model2 = wf2.setReader(simplePassengerForCV).train()
    val data2 = model2.score(keepRawFeatures = false, keepIntermediateFeatures = false)

    compare(data1, data2, pred1, pred2, Some(prob1), Some(prob2))

    val summary = model1.summary()
    log.info(summary)
    summary should include (classOf[SanityChecker].getSimpleName)
    summary should include (""""HoldoutEvaluation" : [ "com.salesforce.op.evaluators.MultiMetrics"""")
    summary should include ("TrainEvaluation")
  }

  it should "return a regression model that runs cv at the workflow level" in new PassenserCSVforCV {
    val fv = Seq(sex, fair, pClass, cabin, age).transmogrify()
    val checked = survivedNum.sanityCheck(fv)

    val pred1 = new RegressionModelSelector(
      validator = new OpCrossValidation(evaluator = Evaluators.Regression.r2()),
      dataSplitter = None,
      evaluators = Seq(new OpRegressionEvaluator())
    ).setModelsToTry(RegressionModelsToTry.LinearRegression, RegressionModelsToTry.RandomForestRegression)
      .setLinearRegressionElasticNetParam(0.01)
      .setRandomForestMinInfoGain(10000)
      .setInput(survivedNum, checked)
      .getOutput()

    val wf1 = new OpWorkflow().withWorkflowCV.setResultFeatures(pred1)
    wf1.isWorkflowCV shouldBe true
    val model1 = wf1.setReader(simplePassengerForCV).train()
    val data1 = model1.score(keepRawFeatures = false, keepIntermediateFeatures = false)

    val pred2 = new RegressionModelSelector(
      validator = new OpCrossValidation(evaluator = Evaluators.Regression.r2(), parallelism = 4),
      dataSplitter = None,
      evaluators = Seq(new OpRegressionEvaluator())
    ).setModelsToTry(RegressionModelsToTry.LinearRegression, RegressionModelsToTry.RandomForestRegression)
      .setLinearRegressionElasticNetParam(0.01)
      .setRandomForestMinInfoGain(10000)
      .setInput(survivedNum, checked)
      .getOutput()

    val wf2 = new OpWorkflow().setResultFeatures(pred2)
    wf2.isWorkflowCV shouldBe false
    val model2 = wf2.setReader(simplePassengerForCV).train()
    val data2 = model2.score(keepRawFeatures = false, keepIntermediateFeatures = false)

    compare(data1, data2, pred1, pred2, None, None)

    val summary = model1.summary()
    log.info(summary)
    summary should include (classOf[SanityChecker].getSimpleName)
    summary should include ("TrainEvaluation")
  }

  it should "return a regression model that runs ts at the workflow level" in new PassenserCSVforCV {
    val fv = Seq(sex, fair, pClass, cabin, age).transmogrify()
    val checked = survivedNum.sanityCheck(fv)

    val pred1 = new RegressionModelSelector(
      validator = new OpTrainValidationSplit(evaluator = Evaluators.Regression.r2()),
      dataSplitter = Option(DataSplitter(seed = 0L)),
      evaluators = Seq(new OpRegressionEvaluator())
    ).setModelsToTry(RegressionModelsToTry.LinearRegression, RegressionModelsToTry.GBTRegression)
      .setLinearRegressionRegParam(100000)
      .setGradientBoostedTreeLossType(LossType.Absolute)
      .setInput(survivedNum, checked)
      .getOutput()

    val wf1 = new OpWorkflow().withWorkflowCV.setResultFeatures(pred1)
    wf1.isWorkflowCV shouldBe true
    val model1 = wf1.setReader(simplePassengerForCV).train()
    val data1 = model1.score(keepRawFeatures = false, keepIntermediateFeatures = false)

    val pred2 = new RegressionModelSelector(
      validator = new OpTrainValidationSplit(evaluator = Evaluators.Regression.r2(), parallelism = 4),
      dataSplitter = Option(DataSplitter(seed = 0L)),
      evaluators = Seq(new OpRegressionEvaluator())
    ).setModelsToTry(RegressionModelsToTry.LinearRegression, RegressionModelsToTry.GBTRegression)
      .setLinearRegressionRegParam(100000)
      .setGradientBoostedTreeLossType(LossType.Absolute)
      .setInput(survivedNum, checked)
      .getOutput()

    val wf2 = new OpWorkflow().setResultFeatures(pred2)
    wf2.isWorkflowCV shouldBe false
    val model2 = wf2.setReader(simplePassengerForCV).train()
    val data2 = model2.score(keepRawFeatures = false, keepIntermediateFeatures = false)

    compare(data1, data2, pred1, pred2, None, None)

    val summary = model1.summary()
    log.info(summary)
    summary should include (classOf[SanityChecker].getSimpleName)
    summary should include (""""HoldoutEvaluation" : [ "com.salesforce.op.evaluators.MultiMetrics"""")
    summary should include ("TrainEvaluation")
  }

  it should "avoid adding label leakage when feature engineering would introduce it" in new PassenserCSVforCV {
    val fairLeaker = fair.autoBucketize(survivedNum, trackNulls = false)
    val ageLeaker = age.autoBucketize(survivedNum, trackNulls = false)
    val fv = Seq(age, sex, ageLeaker, fairLeaker, pClass, cabin).transmogrify()

    val (pred1, _, _) = new BinaryClassificationModelSelector(
      validator = new OpCrossValidation(
        evaluator = Evaluators.BinaryClassification.auPR(), numFolds = 2, seed = 0L, parallelism = 4
      ),
      splitter = Option(DataBalancer(sampleFraction = 0.01, reserveTestFraction = 0.2, seed = 0L)),
      evaluators = Seq(new OpBinaryClassificationEvaluator)
    ).setModelsToTry(LogisticRegression)
      .setLogisticRegressionRegParam(0.0, 0.001, 0.1)
      .setInput(survivedNum, fv)
      .getOutput()

    val wf1 = new OpWorkflow().withWorkflowCV.setResultFeatures(pred1)
    wf1.isWorkflowCV shouldBe true
    val model1 = wf1.setReader(simplePassengerForCV).train()
    val data1 = model1.score(keepRawFeatures = false, keepIntermediateFeatures = true)

    val (pred2, _, _) = new BinaryClassificationModelSelector(
      validator = new OpCrossValidation(
        evaluator = Evaluators.BinaryClassification.auPR(), numFolds = 2, seed = 0L, parallelism = 8
      ),
      splitter = Option(DataBalancer(sampleFraction = 0.01, reserveTestFraction = 0.2, seed = 0L)),
      evaluators = Seq(new OpBinaryClassificationEvaluator)
    ).setModelsToTry(LogisticRegression)
      .setLogisticRegressionRegParam(0.0, 0.001, 0.1)
      .setInput(survivedNum, fv)
      .getOutput()

    val wf2 = new OpWorkflow().setResultFeatures(pred2)
    wf2.isWorkflowCV shouldBe false
    val model2 = wf2.setReader(simplePassengerForCV).train()
    val data2 = model2.score(keepRawFeatures = false, keepIntermediateFeatures = true)

    val summary1 = model1.modelInsights(pred1)
    log.info("model1.summary: \n{}", summary1)
    val summary2 = model2.modelInsights(pred2)
    log.info("model2.summary: \n{}", summary2)

    summary1.selectedModelInfo.get.validationResults
      .forall(_.metricValues.asInstanceOf[SingleMetric].value < 0.81) shouldBe true
    summary2.selectedModelInfo.get.validationResults
      .forall(_.metricValues.asInstanceOf[SingleMetric].value < 0.81) shouldBe false
  }

  def compare(
    data1: DataFrame,
    data2: DataFrame,
    pred1: FeatureLike[RealNN],
    pred2: FeatureLike[RealNN],
    prob1: Option[FeatureLike[OPVector]],
    prob2: Option[FeatureLike[OPVector]]
  ): Unit = {
    val winner1 = pred1.originStage.asInstanceOf[ModelSelectorBase[_, _]].bestEstimator.get
    val winner2 = pred2.originStage.asInstanceOf[ModelSelectorBase[_, _]].bestEstimator.get
    winner1.estimator.getClass shouldEqual winner2.estimator.getClass
    winner1.estimator.asInstanceOf[PipelineStage].extractParamMap.toSeq.sortBy(_.param.name).map(_.value) should
      contain theSameElementsAs
      winner2.estimator.asInstanceOf[PipelineStage].extractParamMap.toSeq.sortBy(_.param.name).map(_.value)

    val d1s = data1.select(pred1.name, prob1.map(_.name).toSeq :+ KeyFieldName: _*).sort(KeyFieldName).collect()
    val d2s = data2.select(pred2.name, prob2.map(_.name).toSeq :+ KeyFieldName: _*).sort(KeyFieldName).collect()
    d1s.zip(d2s).foreach {
      case (r1, r2) =>
        math.abs(r1.getDouble(0) - r2.getDouble(0)) should be < 0.5
        if (r1.size > 2) math.abs(r1.getAs[Vector](1)(0) - r2.getAs[Vector](1)(0)) should be < 0.5
    }
  }

}

class Leaker(uid: String = UID[BinaryTransformer[_, _, _]]) extends
  BinaryTransformer[Real, RealNN, RealNN](operationName = "makeLeaker", uid = uid) {
  override def transformFn: (Real, RealNN) => RealNN =
    (f: Real, l: RealNN) => if (l.v.exists(_ > 0)) 1.0.toRealNN else 0.0.toRealNN
  override def outputIsResponse: Boolean = false
}
