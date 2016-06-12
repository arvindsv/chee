package chee.query

import better.files.File
import chee.metadata.MetadataFile
import org.scalatest._
import chee.properties._
import chee.properties.Patterns._
import chee.TestInfo
import FormatPatterns.lisp
import scala.util.Success

class SqliteBackendTest extends FlatSpec with Matchers with chee.FileLoan {

  val filename = Patterns.lookup(Ident.filename).right.result _
  val checksum = Patterns.lookup(Ident.checksum).right.result _

  "find" should "load pages of results" in {
    val sqlite = new SqliteBackend(TestInfo.sampleDb, None, 2)
    val stream = sqlite.find(TrueCondition).get
    stream.map(filename).toList.sorted should be (
      List("CIMG2590_s.JPG", "CIMG2590_s.JPG", "IMG_7437_s.JPG", "IMG_7437_s.JPG", "test1.jpg", "test1.jpg"))
  }

  it should "resolve pixel property" in {
    val sqlite = new SqliteBackend(TestInfo.sampleDb, None, 2)
    val stream = sqlite.find(Prop(Comp.Gt, VirtualProperty.defaults.pixel.ident -> "300")).get
    stream.map(filename).toList.sorted should be (
      List("CIMG2590_s.JPG", "CIMG2590_s.JPG", "IMG_7437_s.JPG", "IMG_7437_s.JPG", "test1.jpg", "test1.jpg"))
  }

  it should "do like on dates" in {
    val sqlite = new SqliteBackend(TestInfo.sampleDb, None, 2)
    val stream = sqlite.find(Prop(Comp.Like, Ident.lastModified -> "2015*")).get
    stream.map(filename).toList.sorted should be (
      List("CIMG2590_s.JPG", "CIMG2590_s.JPG", "IMG_7437_s.JPG", "IMG_7437_s.JPG", "test1.jpg", "test1.jpg"))
  }

  it should "add all properties" in withNewFile { file =>
    val sqlite = new SqliteBackend(file, None)
    val map0 = LazyMap.fromFile(TestInfo.images.head, MetadataFile.empty) + (Ident.location -> "./")
    sqlite.insert(Seq(map0), 0, Progress.empty[Boolean, Int]).get

    val maps = sqlite.find(TrueCondition).get.toList
    maps should have size (1)
    lisp.result(maps(0)) should be (lisp.result(map0))
  }

  it should "use in operator" in {
    val sqlite = new SqliteBackend(TestInfo.sampleDb, None)
    val Success(result1) = sqlite.find(Condition.or(Prop(Comp.Like, Ident.extension -> "jpg"), Prop(Comp.Like, Ident.extension -> "png")))
    val Success(result2) = sqlite.find(In(Ident.extension, List("jpg", "png")))
    result1 should not be ('empty)
    result1.map(lisp.result) should be (result2.map(lisp.result))
  }

  it should "relativize path when in repo mode" in {
    val path = "/home/eike/workspace/projects/chee2/src/test/resources/images/test1.jpg"
    val sqlite = new SqliteBackend(TestInfo.sampleRepoDb, Some(File("/home/eike/workspace")))
    val Success(result1) = sqlite.find(Prop(Comp.Like, Ident.path -> path))
    result1.size should be (1)
    val Success(result2) = sqlite.find(Prop(Comp.Like, Ident.location -> "/home/eike/workspace/projects/chee2"))
    result2.size should be (6)
    val Success(result3) = sqlite.find(In(Ident.path, List(path)))
    result3.size should be (1)
  }

  "exists" should "check for existing files" in withNewFile { file =>
    val sqlite = new SqliteBackend(file, None)
    val map0 = LazyMap.fromFile(TestInfo.images.head)

    sqlite.insert(Seq(map0), 0, Progress.empty[Boolean, Int]).get

    sqlite.idExists.result(LazyMap(Ident.checksum -> "abc")).get should be (false)
    sqlite.idExists.result(map0).get should be (true)
  }
}
