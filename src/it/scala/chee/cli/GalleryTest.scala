package chee.cli

import better.files._
import chee.it.CommandSetup.Setup
import chee.{TestInfo, UserError}
import chee.it._
import chee.util.files._
import com.typesafe.scalalogging.LazyLogging
import java.util.zip.ZipFile
import org.scalatest._
import scala.collection.JavaConverters._

class GalleryTest extends FlatSpec with Matchers with CommandSetup with FindHelper with LazyLogging {

  def gallery = new Gallery with BufferOut
  def locUpdate = new LocationUpdate(new LocationAdd with BufferOut) with BufferOut


  val copyImagesToDir: Setup => Setup = withSetup { setup =>
    val target = setup.files / "again"
    target.createDirectories()
    setup.files.list.toList.foreach {
      case RegularFile(f) =>
        f.copyTo(target / f.name)
      case _ =>
    }
  }

  val copyImagesToName: Setup => Setup = withSetup { setup =>
    setup.files.list.toList.foreach {
      case RegularFile(f) =>
        f.copyTo(f.mapBaseName(_ + "_1"))
      case _ =>
    }
  }

  val syncLocation: Setup => Setup = withSetup { setup =>
    locUpdate.run(setup, "--all")
    val (out, Nil) = findLisp(setup)
    out.size should be (8)
  }

  val duplicateImagesDir = addImages andThen copyImagesToDir andThen syncLocation
  val duplicateImagesName = addImages andThen copyImagesToName andThen syncLocation

  def testGallery(thumbSize: Int, imageSize: Int, originalSize: Int, setup: Setup): Unit = {
    val cwd = setup.dirs.userDir.get

    val dir = cwd / "gallery"
    gallery.run(setup, "--out", dir.pathAsString, "--link-original")
    (dir / "thumbnails").list should have size (thumbSize)
    (dir / "originals").list should have size (originalSize)
    (dir / "images").list should have size (imageSize)
    (dir / "index.html").exists should be (true)

    val zip = cwd / "gallery.zip"
    gallery.run(setup, "--out", zip.pathAsString, "--link-original")
    zip.exists should be (true)
    val entries = zipEntries(zip)
    entries.filter(_ startsWith "thumbnails") should have size (thumbSize)
    entries.filter(_ startsWith "originals") should have size (originalSize)
    entries.filter(_ startsWith "images") should have size (imageSize)
    entries.filter(_ == "index.html") should have size (1)
  }

  "Gallery" should "not add same thumbnails (1)" in bothChee(duplicateImagesDir) { setup =>
    testGallery(4, 4, 4, setup)
  }

  it should "not add same thumbnails (2)" in bothChee(duplicateImagesName) { setup =>
    testGallery(4, 7, 8, setup)
    // 7 is because only one image needs to be scaled (and is reused)
    // images/nixos_logo_1.png, images/CIMG2590_s.JPG, images/IMG_7437_s.JPG,
    // images/b6bdc5b62c489ebfa55738fb4-scale-1400x933.jpg, images/IMG_7437_s_1.JPG, images/CIMG2590_s_1.JPG,
    // images/nixos_logo.png
  }

  it should "render format patterns" in bothChee(addImages) { setup =>
    val cwd = setup.dirs.userDir.get
    val template = cwd / "template.mustache"
    template `<<` "{{#files}}{{#_format}}~:length{{/_format}} {{/files}}"
    gallery.run(setup, "--template", template.pathAsString, "--out", (cwd / "gallery").pathAsString)
    (cwd / "gallery" / "index.html").contentAsString.trim should be ("6.6kb 47.6kb 19.4kb 303.8kb")
  }


  def zipEntries(zip: File): Seq[String] = {
    val zipFile = new ZipFile(zip.toJava)
    val r = zipFile.entries().asScala.map(e => e.getName).toList
    zipFile.close()
    r
  }
}
