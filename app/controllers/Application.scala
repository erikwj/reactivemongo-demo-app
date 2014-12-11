package controllers

import org.joda.time.DateTime
import scala.concurrent.Future

import play.api.Logger
import play.api.Play.current
import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.reactivemongo.{ MongoController, ReactiveMongoPlugin }

import reactivemongo.api.gridfs.GridFS
import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson._

import models.Topic
import models.Topic._

object Topics extends Controller with MongoController {

  // get the collection 'topics'
  val collection = db[BSONCollection]("topics")
  // a GridFS store named 'attachments'
  //val gridFS = new GridFS(db, "attachments")
  val gridFS = new GridFS(db)

  // let's build an index on our gridfs chunks collection if none
  gridFS.ensureIndex().onComplete {
    case index =>
      Logger.info(s"Checked index, result is $index")
  }

  // list all topics and sort them
  def index = Action.async { implicit request =>
    // get a sort document (see getSort method for more information)
    val sort = getSort(request)
    // build a selection document with an empty query and a sort subdocument ('$orderby')
    val query = BSONDocument(
      "$orderby" -> sort,
      "$query" -> BSONDocument())
    val activeSort = request.queryString.get("sort").flatMap(_.headOption).getOrElse("none")
    // the cursor of documents
    val found = collection.find(query).cursor[Topic]
    // build (asynchronously) a list containing all the topics
    found.collect[List]().map { topics =>
      Ok(views.html.topics(topics, activeSort))
    }.recover {
      case e =>
        e.printStackTrace()
        BadRequest(e.getMessage())
    }
  }

  def showCreationForm = Action {
    Ok(views.html.editTopic(None, Topic.form, None))
  }

  def showEditForm(id: String) = Action.async {
    val objectId = BSONObjectID(id)
    // get the documents having this id (there will be 0 or 1 result)
    val futureTopic = collection.find(BSONDocument("_id" -> objectId)).one[Topic]
    // ... so we get optionally the matching article, if any
    // let's use for-comprehensions to compose futures (see http://doc.akka.io/docs/akka/2.0.3/scala/futures.html#For_Comprehensions for more information)
    for {
      // get a future option of article
      maybeTopic <- futureTopic
      // if there is some article, return a future of result with the article and its attachments
      result <- maybeTopic.map { article =>
        import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
        // search for the matching attachments
        // find(...).toList returns a future list of documents (here, a future list of ReadFileEntry)
        gridFS.find(BSONDocument("article" -> article.id.get)).collect[List]().map { files =>
          val filesWithId = files.map { file =>
            file.id.asInstanceOf[BSONObjectID].stringify -> file
          }
          Ok(views.html.editTopic(Some(id), Topic.form.fill(article), Some(filesWithId)))
        }
      }.getOrElse(Future(NotFound))
    } yield result
  }

  def create = Action.async { implicit request =>
    Topic.form.bindFromRequest.fold(
      errors => Future.successful(Ok(views.html.editTopic(None, errors, None))),
      // if no error, then insert the article into the 'topics' collection
      article =>
        collection.insert(article.copy(creationDate = Some(new DateTime()), updateDate = Some(new DateTime()))).map(_ =>
          Redirect(routes.Topics.index))
    )
  }

  def edit(id: String) = Action.async { implicit request =>
    Topic.form.bindFromRequest.fold(
      errors => Future.successful(Ok(views.html.editTopic(Some(id), errors, None))),
      article => {
        val objectId = BSONObjectID(id)
        // create a modifier document, ie a document that contains the update operations to run onto the documents matching the query
        val modifier = BSONDocument(
          // this modifier will set the fields 'updateDate', 'title' and 'content'
          "$set" -> BSONDocument(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "title" -> BSONString(article.title),
            "content" -> BSONString(article.content)
           ))
        // ok, let's do the update
        collection.update(BSONDocument("_id" -> objectId), modifier).map { _ =>
          Redirect(routes.Topics.index)
        }
      })
  }

  def delete(id: String) = Action.async {
    // let's collect all the attachments matching that match the article to delete
    gridFS.find(BSONDocument("article" -> BSONObjectID(id))).collect[List]().flatMap { files =>
      // for each attachment, delete their chunks and then their file entry
      val deletions = files.map { file =>
        gridFS.remove(file)
      }
      Future.sequence(deletions)
    }.flatMap { _ =>
      // now, the last operation: remove the article
      collection.remove(BSONDocument("_id" -> BSONObjectID(id)))
    }.map(_ => Ok).recover { case _ => InternalServerError }
  }

  // save the uploaded file as an attachment of the article with the given id
  def saveAttachment(id: String) = Action.async(gridFSBodyParser(gridFS)) { request =>
    // here is the future file!
    val futureFile = request.body.files.head.ref
    // when the upload is complete, we add the article id to the file entry (in order to find the attachments of the article)
    val futureUpdate = for {
      file <- futureFile
      // here, the file is completely uploaded, so it is time to update the article
      updateResult <- {
        gridFS.files.update(
          BSONDocument("_id" -> file.id),
          BSONDocument("$set" -> BSONDocument("article" -> BSONObjectID(id))))
      }
    } yield updateResult

    futureUpdate.map {
      case _ => Redirect(routes.Topics.showEditForm(id))
    }.recover {
      case e => InternalServerError(e.getMessage())
    }
  }

  def getAttachment(id: String) = Action.async { request =>
    // find the matching attachment, if any, and streams it to the client
    val file = gridFS.find(BSONDocument("_id" -> BSONObjectID(id)))
    request.getQueryString("inline") match {
      case Some("true") => serve(gridFS, file, CONTENT_DISPOSITION_INLINE)
      case _            => serve(gridFS, file)
    }
  }

  def removeAttachment(id: String) = Action.async {
    gridFS.remove(BSONObjectID(id)).map(_ => Ok).recover { case _ => InternalServerError }
  }

  private def getSort(request: Request[_]) = {
    request.queryString.get("sort").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          if (field.startsWith("-"))
            field.drop(1) -> -1
          else field -> 1
        }
        if order._1 == "title" || order._1 == "creationDate" || order._1 == "updateDate"
      } yield order._1 -> BSONInteger(order._2)
      BSONDocument(sortBy)
    }
  }
}




/*

import scala.collection.JavaConversions._
import scala.util.matching.Regex
import com.faqtfinding.tools._
import concurrent.Future
import concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}

import java.io.File
import java.util.Base64

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.Path


//import java.util.concurrent.Executors
//import concurrent.ExecutionContext
//val executorService = Executors.newFixedThreadPool(4)
//val executionContext = ExecutionContext.fromExecutorService(executorService)


val IMAGE_FORMAT = ".png"

/*
 * Regex for filtering extension
 */
val file_r = """(.*)(\.[a-zA-Z]+)$""".r

def getFiles(dir: File,r: Regex): Stream[File] = 
  if (dir.isDirectory) dir.listFiles().toStream.filter(f => r.findFirstIn(f.getName).isDefined)
  else Stream.Empty

val uploadedFile = new File("/Users/erikjanssen/ScalaProjects/cli/resources/Layouts.pdf")
val dir = new File(uploadedFile.getParent)
val fname = uploadedFile.getName



val (sourcePath,destinationPath) = fname match { 
  case file_r(name,ext) => (dir + "/" + fname,dir + "/" + name + IMAGE_FORMAT) }

//if(sourcePath == destinationPath ) saveToGrid(source)
//else convertToPng(sourcePath,destinationPath)

//val pdf = validPdf(uploadedFile)

val pdf = new File(sourcePath)
val imgtemplate = new File(destinationPath)

val convertedPngRegex = imgtemplate.getName match { 
  case file_r(name,ext) => new Regex(s"""$name""" + """-\d+""" + """\""" + s"""$ext""")
}

val config = new ImageConverterConfig { density := 200 }
val ic = ImageConverter(config)

/*
* Again, if you have long-running computations, having them run in a separate ExecutionContext 
* for CPU-bound tasks is a good idea. How to tune your various thread pools is highly dependent 
* on your individual application and beyond the scope of this article.
*/
val imageConversion = Future { ic.run(pdf,imgtemplate) }

//Specific images
//file.saveTo("/users/registeredusers/summaries/summaryname/images/")
//generic images uploaded by e.g. mail
//file.saveTo("/users/registeredusers/images/")

val encoder = Base64.getEncoder()

val result = imageConversion.onComplete {
    case Success(rc) => 
      val files = getFiles(dir,convertedPngRegex )
      //files map {(png) => saveToGrid(png)}
      files.foreach((pdf) => println(encoder.encodeToString(Files.readAllBytes(pdf.toPath)).substring(0,100)))
    case Failure(ex) =>
      println(s"Pdf couldn't be converted to $IMAGE_FORMAT: ${ex.getMessage}")
  }

<img src=”data:<MIMETYPE>;base64,<BASE64_ENCODED_IMAGE>”>

def imageToHtml(format:String,dataB64:String) = s"""<img src=”data:image/$format;base64,$dataB64">"""

<img src="images/photo.jpg" width="400" height="300" ¬
    alt="A descriptive text of the image" />

<img data-natural-height="899" data-natural-width="1600" style="visibility: visible;" src="https://s3.amazonaws.com/media-p.slid.es/uploads/erikjanssen/images/909665/DSC07668.JPG"></div></div>

*/
