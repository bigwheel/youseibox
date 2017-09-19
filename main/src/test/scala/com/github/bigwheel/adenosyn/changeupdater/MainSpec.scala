package com.github.bigwheel.adenosyn.changeupdater

import com.github.bigwheel.adenosyn.changeloggermanager.ChangeLoggerManager
import com.github.bigwheel.adenosyn.recordstojson.Assembler
import com.github.bigwheel.adenosyn.recordstojson.dsl._
import com.github.bigwheel.adenosyn.sqlutil
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.ElasticsearchClientUri
import com.sksamuel.elastic4s.IndexAndType
import com.sksamuel.elastic4s.source.JsonDocumentSource
import org.elasticsearch.common.settings.Settings
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FunSpec
import org.scalatest.Matchers
import org.slf4j.LoggerFactory
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scalaz.Scalaz._
import scalikejdbc.Commons2ConnectionPoolFactory
import scalikejdbc.ConnectionPool
import scalikejdbc.DB
import scalikejdbc.NamedDB

class MainSpec extends FunSpec with Matchers with BeforeAndAfter with BeforeAndAfterAll {

  override def beforeAll() = {
    val l = LoggerFactory.getLogger(getClass)
    Process("docker-compose up -d").!(ProcessLogger(l.debug, l.warn))

    _root_.com.github.bigwheel.adenosyn.sqlutil.suppressLog()
    Class.forName("com.mysql.jdbc.Driver")
    ConnectionPool.singleton(sqlutil.url(), "root", "root")
    ConnectionPool.add('observee, sqlutil.url("observee"), "root", "root")
    ConnectionPool.add('record, sqlutil.url("record"), "root", "root")
  }

  private[this] val elasticsearchUrl = ElasticsearchClientUri("127.0.0.1", 9300)
  private[this] val client = ElasticClient.transport(
    Settings.settingsBuilder.put("cluster_name", "elasticsearch").build(), elasticsearchUrl
  )

  before {
    client.execute {
      deleteIndex("_all")
    }.await
  }

  after {
    client.close
  }

  private[this] def withDatabases(test: => Any) {
    DB.autoCommit { implicit session =>
      sqlutil.executeStatements(
        """DROP DATABASE IF EXISTS observee;
          |CREATE DATABASE         observee;
          |DROP DATABASE IF EXISTS record;
          |CREATE DATABASE         record;
          |DROP USER IF EXISTS 'adenosyn'@'%';
          |CREATE USER         'adenosyn'@'%' IDENTIFIED BY 'yb';
          |GRANT ALL ON observee.* TO 'adenosyn'@'%';
          |GRANT ALL ON record.*   TO 'adenosyn'@'%';""".stripMargin
      )
    }
    NamedDB('observee).autoCommit { implicit session =>
      sqlutil.executeScript("/fixture.sql")
    }

    test
  }

  it("a") {
    withDatabases {
      val structure = JsObject(
        RootJoinDefinition(
          Table(
            "artist",
            JoinDefinition("id" -> "Int", false, "artist_id" -> "Int", Table("artist_kana"))
          )
        ).some,
        Map[String, JsValue](
          "_id" -> JsString("artist", "id"),
          "name" -> JsString("artist", "name"),
          "kana" -> JsString("artist_kana", "kana")
        )
      )
      val subject = new Subject(elasticsearchUrl, Seq((structure, IndexAndType("index1", "type1"))))
      subject.buildAll
      client.execute {
        flush index "index1"
      }.await
      val response = client.execute {
        search in "index1" -> "type1"
      }.await
      response.hits.map(_.id) should be(Array("1"))
    }
  }

  class Subject(elasticsearchClientUri: ElasticsearchClientUri,
    mappings: Seq[(JsValue, IndexAndType)]) {
    private[this] val url = sqlutil.url()
    private[this] val username = "adenosyn"
    private[this] val password = "yb"

    val cr = new ChangeLoggerManager(url, "observee", "record", username, password)

    def buildAll() = {
      val client = ElasticClient.transport(Settings.settingsBuilder.put("cluster_name",
        "elasticsearch").build(), elasticsearchUrl)

      val pool = Commons2ConnectionPoolFactory(url, username, password)
      scalikejdbc.using(DB(pool.borrow)) { db =>
        db.conn.setCatalog("observee")
        db.autoCommit { implicit session =>
          val a = new Assembler
          val json = a.AssembleAll(mappings.head._1).head
          val noIdJson = json.hcursor.downField("_id").delete.undo.get
          val idString = json.field("_id").get.string.get
          client.execute {
            index into mappings.head._2 doc JsonDocumentSource(noIdJson.nospaces) id idString
          }.await
        }
      }


      client.close
    }
  }

}
