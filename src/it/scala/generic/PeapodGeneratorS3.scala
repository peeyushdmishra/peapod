package generic

import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.hadoop.fs.{FileSystem, Path}
import peapod.{Peapod, Web}

import scala.util.Random

object PeapodGeneratorS3 {
  def createTempDir(): String = {
    val sdf = new SimpleDateFormat("ddMMyy-hhmmss")
    val path = "s3n://mindfulmachines-tests/peapod/tmp/"+ "workflow-" + sdf.format(new Date()) + Random.nextInt()
    val fs = FileSystem.get(new URI(path), SparkS3.sc.hadoopConfiguration)
    fs.mkdirs(new Path(path))
    fs.deleteOnExit(new Path(path))
    path
  }

  def peapod(conf: Config  = ConfigFactory.load()) = {
    val path = createTempDir()
    val w = new Peapod(
      path= path,
      raw="s3n://mindfulmachines-tests/peapod/raw/",
      conf = conf)(generic.SparkS3.sc)
    w
  }
  def peapodNonRecursive(conf: Config  = ConfigFactory.load()) = {
    val path = createTempDir()
    val w = new Peapod(
      path= path,
      raw="s3n://mindfulmachines-tests/peapod/raw/",
      conf = conf)(generic.SparkS3.sc) {
      override val recursiveVersioning = false
    }
    w
  }
}
