package peapod

import generic.PeapodGeneratorS3
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.feature.{HashingTF, StopWordsRemover, Tokenizer}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.scalatest.FunSuite
import peapod.StorableTask._
import PeapodWorkflowS3Test._

object PeapodWorkflowS3Test {
  var runs = 0

  def upRuns() = this.synchronized {
    runs+=1
  }

  case class DependencyInput(label: Double, text: String)

  class Raw(implicit val p: Peapod) extends StorableTask[RDD[DependencyInput]]
     {
    override val version = "2"
    override val description = "Loading data from dependency.csv"
    def generate = {
      upRuns()
      p.sc.textFile(p.raw + "/dependency.csv")
        .map(_.split(","))
        .map(l => new DependencyInput(l(0).toDouble, l(1)))
    }
  }

  class Parsed(implicit val p: Peapod) extends StorableTask[DataFrame] {
    import p.sqlCtx.implicits._
    val raw = pea(new Raw)
    def generate = {
      upRuns()
      raw().toDF()
    }
  }

  class ParsedEphemeral(implicit val p: Peapod) extends EphemeralTask[DataFrame] {
    import p.sqlCtx.implicits._
    val raw = pea(new Raw)
    def generate = {
      upRuns()
      raw().toDF()
    }
  }

  class PipelineFeature(implicit val p: Peapod) extends StorableTask[PipelineModel] {
    val parsed = pea(new Parsed)
    override val version = "2"
    def generate = {
      upRuns()
      val training = parsed.get()
      val tokenizer = new Tokenizer()
        .setInputCol("text")
        .setOutputCol("TextTokenRaw")
      val remover = new (StopWordsRemover)
        .setInputCol(tokenizer.getOutputCol)
        .setOutputCol("TextToken")
      val hashingTF = new HashingTF()
        .setNumFeatures(5)
        .setInputCol(remover.getOutputCol)
        .setOutputCol("features")

      val pipeline = new org.apache.spark.ml.Pipeline()
        .setStages(Array(tokenizer,remover, hashingTF))
      pipeline.fit(training)
    }
  }

  class PipelineLR(implicit val p: Peapod) extends StorableTask[PipelineModel] {
    val pipelineFeature = pea(new PipelineFeature())
    val parsed = pea(new Parsed)
    def generate = {
      upRuns()
      val training = parsed
      val lr = new LogisticRegression()
        .setMaxIter(25)
        .setRegParam(0.01)
        .setFeaturesCol("features")
        .setLabelCol("label")
      val pipeline = new org.apache.spark.ml.Pipeline()
        .setStages(Array(lr))
      pipeline.fit(pipelineFeature.get().transform(training.get()))
    }
  }

  class AUC(implicit val p: Peapod) extends StorableTask[Double]  {
    override val description = "AUC generated for a model"
    val pipelineLR = pea(new PipelineLR())
    val pipelineFeature = pea(new PipelineFeature())
    val parsed = pea(new Parsed)
    def generate = {
      upRuns()
      val training = parsed.get()
      val transformed = pipelineFeature.get().transform(training)
      val predictions = pipelineLR.get().transform(transformed)
      val evaluator = new BinaryClassificationEvaluator()
      evaluator.evaluate(predictions)
    }
  }

}

class PeapodWorkflowS3Test extends FunSuite {
  test("testRunWorkflowConcurrentCache") {
    implicit val w = PeapodGeneratorS3.peapod()
    runs = 0
    (1 to 10).par.foreach{i => w.pea(new AUC()).get()}
    assert(runs == 5)
    Thread.sleep(1000)
    runs = 0
    (1 to 10).par.foreach{i => w.pea(new AUC()).get()}
    assert(runs == 0)
  }
  test("testRunWorkflow") {
    implicit val w = PeapodGeneratorS3.peapod()
    runs = 0
    w(new PipelineFeature()).get()
    w(new ParsedEphemeral())
    w(new AUC())

    w.pea(new AUC()).get()

    assert(runs == 5)

    runs = 0
    w.pea(new AUC()).get()

    assert(runs == 0)

    runs = 0

    w.pea(new ParsedEphemeral()).get()

    assert(runs == 1)
    assert(w.size() == 6)

  }

  test("testRunWorkflowConcurrent") {
    implicit val w = PeapodGeneratorS3.peapod()
    runs = 0
    (1 to 10).par.foreach{i => w.pea(new AUC()).get()}
    assert(runs == 5)
  }


}