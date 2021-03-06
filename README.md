Peapod
==============

Peapod is a dependency and data pipeline management framework for Spark and Scala. The goals were to provide a framework that is simple to use, automatically saves/loads the output of tasks, and provides support for versioning. It is a work in progress and still very much experimental so new versions may introduce breaking changes.

Please let us know what you think and follow our blog [Mindful Machines](http://www.mindfulmachines.io).

# Getting Started

If you're using maven then just add this:
```xml
<dependency>
    <groupId>io.mindfulmachines</groupId>
    <artifactId>peapod_2.11</artifactId>
    <version>0.8</version>
</dependency>
```
If you're using SBT then just add this or clone the repository:
```scala
libraryDependencies += "io.mindfulmachines" %% "peapod" % "0.8"
```

# Using

The first step is to create a Task. A StorableTask will automatically save and load the output of the Task's generate method (in this case a RDD[(Long,String)]) to disk. If an appropriate serialized version exists it will be loaded, otherwise it will be generated and then saved. We'll discuss where the storage happens a bit further down. StorableTask can only serialize, currently, RDDs, DataFrames and Serializable classes. You can override the read and write methods to save/laod other types of classes.
```scala
import peapod.{Peapod, StorableTask}
import peapod.StorableTask._
class RawPages(implicit val p: Peapod) extends StorableTask[RDD[(Long, String)]] {
  def generate =
    readWikiDump(p.sc, p.raw + "/enwiki-20150304-pages-articles.xml.bz2")
}
```
Then you can create other tasks which depend on this task. You use the pea() method to wrap a task to have it be a dependency of the current task. You can then use the get() method of the dependency to access the output of the dependencies generate method. The outputs are cached so even if you create multiple instances of a Task (within a single Peapod, we'll get to that in a bit) their get methods will all point to the same data.
```scala
import peapod.{Peapod, StorableTask}
import peapod.StorableTask._
class ParsedPages(implicit val p: Peapod) extends StorableTask[RDD[Page]] {
  val rawPages = pea(new RawPages)
  def generate =
    parsePages(rawPages.get()).map(_._2)
        //Remove duplicate pages with the same title
        .keyBy(_.title).reduceByKey((l,r) => l).map(_._2)
}
```
To actually run Tasks you need to create a Peapod which "holds" tasks, keeps shared variables (such as where Task outputs are saved/loaded from) and manages shared state between Task instances. In the below example Task state would be stored in "s3n://tm-bucket/peapod-pipeline". If you use s3 be sure to set the appropriate values for fs.s3n.awsAccessKeyId and fs.s3n.awsSecretAccessKey in the SparkContext's hadoopConfiguration.
```scala
implicit val p = new Peapod(
  path="s3n://tm-bucket/peapod-pipeline",
  raw="s3n://tm-bucket/peapod-input"
)
```
You can now run Task's and get their outputs. Dependencies will be automatically run if necessary if the current Task isn't saved to disk.
```scala
p(new ParsedPages()).get().count()
```
Tasks support versioning and the versioning flows through the pipeline so all dependent Tasks are also re-generated if the version of their dependency changes. For the below would cause ParsedPages and RawPages to be re-run even if they had previously had their output stored.
```scala
import peapod.StorableTask._
class RawPages(implicit val p: Peapod) extends StorableTask[RDD[(Long, String)]] {
  override val version = "2"
  def generate =
    readWikiDump(p.sc, p.raw + "/enwiki-20150304-pages-articles.xml.bz2")
}
p(new ParsedPages()).get().count()
```

# Detailed Description

In progress.

## Ephemeral Task
This is a Task which never saves or loads it's state from disk but always runs the generate method. This is useful for quick Tasks or Tasks which only run for a short period of time.
```scala
class RawPages(implicit val p: Peapod) extends EphemeralTask[RDD[(Long, String)]] {
  def generate =
    readWikiDump(p.sc, p.raw + "/enwiki-20150304-pages-articles.xml.bz2")
}
```

## Storable Task
This is a Task which saves or loads it's state (the output of generate) from disk. You need to run `import peapod.StorableTask._` first to ensure that the implicit conversions for serialization are imported.
```scala
import peapod.StorableTask._
class RawPages(implicit val p: Peapod) extends StorableTask[RDD[(Long, String)]] {
  def generate =
    readWikiDump(p.sc, p.raw + "/enwiki-20150304-pages-articles.xml.bz2")
}
```

## Caching/Persisting
The Peapod framework will in some cases automatically run Spark's persist methods on the output of tasks (for RDD, DataFrame and Dataset outputs) and so cache them. This is currently done if a task is ephemeral or if it has more than two tasks which depend on it. Once the number of dependent tasks that have not been already generated is zero the task will be automatically unpersisted. It's also possible to manually control the persistence using the cache variable in a Task:
```scala
class RawPages(implicit val p: Peapod) extends EphemeralTask[RDD[(Long, String)]] {
  //The default is Auto
  override val persist = Auto
  //Never persists this task's output:
  //override val persist = Never
  //Always persist this task's output:
  //override val persist = Always
}
```

## Parametrization
There are times when you want Tasks to take in parameters in real time as part of their constructor. This can allow for clean custom pipelines without global state or for things like iterative computation and loops. For this to work cleanly with Peapod, however, you would need to have the name of the Task take the parameter into account. Doing this manually is possible but tedious so the functionality is now built into Peapod.

To achieve this you first need to define your parameters as traits with the parameter as a variable and use the `param()` method to register it.
```scala
trait ParamA extends Param {
  val a: String
  param(a)
}
trait ParamB extends Param {
  val b: String
  param(b)
}
```
Then you simply have your Tasks inherit the traits and define the parameters in the constructor:

```scala
class Test(val a: String, val b: String)(implicit val p: Peapod)
  extends EphemeralTask[Double] with ParamA with ParamB {
  def generate = 1
}
```

The name of the resulting task is automatically updated based on the parameters:
```scala
new Test("a","b").name
//"peapod.ParamTest$Test_98_97"
new Test("a","a").name
//"peapod.ParamTest$Test_97_97"
```

## Dot Graphs
There is support for outputting the dependency tree into a [Dot format](https://en.wikipedia.org/wiki/DOT_(graph_description_language)). Dotted boxes indicate EphemeralTasks and filled in boxes indicate tasks that already are stored on disk.
```scala
new Test.AUC()
println(p.dotFormatDiagram())
```
You can either use a tool such as [Graphiz](http://www.graphviz.org/) or a service such as [Gravizo](http://gravizo.com) to convert the Dot file into an image. We also provide our own service that accepts compressed representations of the Dot Diagram which allows for larger DAGs to be displayed.
```scala
println(Util.gravizoDotLink(p.dotFormatDiagram()))
println(Util.mindfulmachinesDotLink(p.dotFormatDiagram()))
```
In Spark Notebook you can simply create an XML literal for this:
```scala
<img src={Util.gravizoDotLink(p.dotFormatDiagram())}/>
```
![Gravizo Dot Graphic](http://g.gravizo.com/g?digraph%20G%20%7Bnode%20%5Bshape%3Dbox%5D%22dependency.Test%24Parsed%22%20%5Bstyle%3Dfilled%5D%3B%0A%22dependency.Test%24Raw%22%20%5Bstyle%3Dfilled%5D%3B%0A%22dependency.Test%24PipelineFeature%22%20%5Bstyle%3Dfilled%5D%3B%22dependency.Test%24ParsedEphemeral%22%20%5Bstyle%3Ddotted%5D%3B%22dependency.Test%24Parsed%22-%3E%22dependency.Test%24PipelineFeature%22%3B%0A%22dependency.Test%24Raw%22-%3E%22dependency.Test%24ParsedEphemeral%22%3B%0A%22dependency.Test%24Parsed%22-%3E%22dependency.Test%24AUC%22%3B%0A%22dependency.Test%24PipelineFeature%22-%3E%22dependency.Test%24AUC%22%3B%0A%22dependency.Test%24PipelineLR%22-%3E%22dependency.Test%24AUC%22%3B%0A%22dependency.Test%24Parsed%22-%3E%22dependency.Test%24PipelineLR%22%3B%0A%22dependency.Test%24PipelineFeature%22-%3E%22dependency.Test%24PipelineLR%22%3B%0A%22dependency.Test%24Raw%22-%3E%22dependency.Test%24Parsed%22%3B%7B%20rank%3Dsame%3B%22dependency.Test%24ParsedEphemeral%22%20%22dependency.Test%24AUC%22%20%22dependency.Test%24AUC%22%20%22dependency.Test%24AUC%22%7D%7B%20rank%3Dsame%3B%22dependency.Test%24Raw%22%20%22dependency.Test%24Raw%22%7D%7D)

# Web Server (Experimental)
The dot graphs while useful are limited in their flexibility by requiring an explicit generation from the Peapod instance. To allow for a better user experience an experimental web server which displays the DAG graph was added in Peapod 0.8.

To use this you need to extend Peapod with the Web trait:
```scala
val p = new Peapod(
  path= new Path("file://",path.replace("\\","/")).toString,
  raw="")(sc) with Web
```

Then you can go to localhost:8080 to view the DAG graph in real time.

# Known Issues
 * StorableTasks whose generate only performs a map operation on an RDD will not work if S3 is the storage location. You can get around this by running a repartition in the generate.

# The Future
 * Automatic Versioning: Give the option to use the actual code of the task to generate the version and automatically change the version if the code changes.
 * Improve handing of automatic persistence
