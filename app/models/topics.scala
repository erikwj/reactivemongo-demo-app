package models

import org.jboss.netty.buffer._
import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.validation.Constraints._

import reactivemongo.bson._

case class Topic(
  id: Option[BSONObjectID],
  title: String,
  content: String,
  tags:List[String],
  creationDate: Option[DateTime],
  updateDate: Option[DateTime])
// Turn off your mind, relax, and float downstream
// It is not dying...
object Topic {
  implicit object TopicBSONReader extends BSONDocumentReader[Topic] {
    def read(doc: BSONDocument): Topic =
      Topic(
        doc.getAs[BSONObjectID]("_id"),
        doc.getAs[String]("title").get,
        doc.getAs[String]("content").get,
        doc.getAs[List[String]]("tags").toList.flatten,
        doc.getAs[BSONDateTime]("creationDate").map(dt => new DateTime(dt.value)),
        doc.getAs[BSONDateTime]("updateDate").map(dt => new DateTime(dt.value)))
  }
  implicit object TopicBSONWriter extends BSONDocumentWriter[Topic] {
    def write(topic: Topic): BSONDocument =
      BSONDocument(
        "_id" -> topic.id.getOrElse(BSONObjectID.generate),
        "title" -> topic.title,
        "content" -> topic.content,
        "tags" -> topic.tags,
        "creationDate" -> topic.creationDate.map(date => BSONDateTime(date.getMillis)),
        "updateDate" -> topic.updateDate.map(date => BSONDateTime(date.getMillis)))
  }

  val form = Form(
    mapping(
      "id" -> optional(of[String] verifying pattern(
        """[a-fA-F0-9]{24}""".r,
        "constraint.objectId",
        "error.objectId")),
      "title" -> nonEmptyText,
      "content" -> text,
      "tags" -> list(text),
      "creationDate" -> optional(of[Long]),
      "updateDate" -> optional(of[Long])) { (id, title, content,tags, creationDate, updateDate) =>
        Topic(
          id.map(BSONObjectID(_)),
          title,
          content,
          tags,
          creationDate.map(new DateTime(_)),
          updateDate.map(new DateTime(_)))
      } { topic =>
        Some(
          (topic.id.map(_.stringify),
            topic.title,
            topic.content,
            topic.tags,
            topic.creationDate.map(_.getMillis),
            topic.updateDate.map(_.getMillis)))
      })
}