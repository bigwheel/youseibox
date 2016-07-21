package com.github.bigwheel.youseibox

import argonaut.Argonaut._
import argonaut._
import com.github.bigwheel.youseibox.json._
import com.github.bigwheel.youseibox.table._
import org.scalatest.FunSpec
import org.scalatest.Matchers
import scala.sys.process.Process
import scalaz.Scalaz._
import scalikejdbc._

class WiringSpec extends FunSpec with Matchers {
  private[this] val ipAddress = Process("otto dev address").!!.stripLineEnd
  private[this] implicit val session = AutoSession

  private[this] def initialize() = {
    Class.forName("com.mysql.jdbc.Driver")
    using(Commons2ConnectionPoolFactory(s"jdbc:mysql://$ipAddress/?useSSL=false", "root", "root")) {
      rootPool =>
        using(DB(rootPool.borrow)) { ghostDb =>
          ghostDb.autoCommit { implicit session =>
            """
              |DROP USER IF EXISTS youseibox
              |CREATE USER 'youseibox'@'%' IDENTIFIED BY 'youseibox'
              |DROP DATABASE IF EXISTS youseibox
              |CREATE DATABASE youseibox
              |DROP DATABASE IF EXISTS youseibox_test
              |CREATE DATABASE youseibox_test
              |GRANT ALL ON youseibox.*      TO 'youseibox'@'%'
              |GRANT ALL ON youseibox_test.* TO 'youseibox'@'%'
              |""".stripMargin.split("\n").withFilter(_ != "").foreach(SQL(_).execute.apply())
          }
        }
    }

    ConnectionPool.singleton(
      s"jdbc:mysql://$ipAddress/youseibox_test?useSSL=false", "youseibox", "youseibox")

    DB.autoCommit { implicit session =>
      Seq(
        "drop table if exists artist",
        "drop table if exists artist_kana",
        "drop table if exists music",
        "drop table if exists content",
        "drop table if exists tieup"
      ).foreach(SQL(_).execute.apply())

      Seq(
        """
        create table artist (
          id   INT  NOT NULL PRIMARY KEY AUTO_INCREMENT,
          name TEXT NOT NULL
        )
        """,
        """
        create table artist_kana (
          artist_id INT  NOT NULL PRIMARY KEY,
          kana      TEXT NOT NULL
        )
        """,
        """
        create table music (
          id        INT  NOT NULL PRIMARY KEY AUTO_INCREMENT,
          artist_id INT  NOT NULL,
          name      TEXT NOT NULL,
          INDEX index_artist_id(artist_id)
        )
        """,
        """
        create table content (
          id       INT  NOT NULL PRIMARY KEY AUTO_INCREMENT,
          music_id INT  NOT NULL,
          name     TEXT NOT NULL,
          INDEX index_music_id(music_id)
        )
        """,
        """
        create table tieup (
          id       INT  NOT NULL PRIMARY KEY AUTO_INCREMENT,
          music_id INT  NOT NULL,
          name     TEXT NOT NULL,
          INDEX index_music_id(music_id)
        )
        """
      ).
        foreach(SQL(_).execute.apply())

      Seq(
        """INSERT INTO artist(id, name) VALUES (1, "水樹奈々")""",
        """INSERT INTO artist_kana(artist_id, kana) VALUES (1, "みずきなな")""",
        """INSERT INTO music(id, artist_id, name) VALUES (11, 1, "深愛")""",
        """INSERT INTO music(id, artist_id, name) VALUES (12, 1, "innocent starter")""",
        """INSERT INTO content(id, music_id, name) VALUES (111, 11, "深愛 - ショートVer.")""",
        """INSERT INTO content(id, music_id, name) VALUES (112, 11, "深愛 - ロングVer.")""",
        """INSERT INTO content(id, music_id, name) VALUES (121, 12, "innocent starter(inst)")""",
        """INSERT INTO tieup(id, music_id, name) VALUES (1101, 11, "White Album 2")""",
        """INSERT INTO tieup(id, music_id, name) VALUES (1201, 12, "魔法少女リリカルなのは")"""
      ).foreach(SQL(_).execute.apply())
    }
  }

  initialize

  /*it("開発環境VMが動いている") { // テスト毎に時間が数秒余計にかかるのでコメントアウト
    val result = Process("otto dev vagrant status").!!
    result.split("\n")(3) should
      equal("default                   running (virtualbox)")
  }*/

  it("開発環境のIPアドレスが正しく取得できる") {
    ipAddress should fullyMatch regex """\A\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\Z"""
  }

  case class TestCase(title: String, input: JsValue, expected: List[Json])

  val tests = Seq[TestCase](
    TestCase(
      "最も単純なjsonオブジェクトを組み立てられる",
      JsObject(
        RootJoinDefinition(Table("artist")).some,
        Map[String, JsValue](
          "name" -> JsString("artist", "name")
        )
      ),
      List(Json("name" := "水樹奈々"))
    ),
    TestCase(
      "複数プロパティのjsonオブジェクトを組み立てられる",
      JsObject(
        RootJoinDefinition(Table("artist")).some,
        Map[String, JsValue](
          "id" -> JsInt("artist", "id"),
          "name" -> JsString("artist", "name")
        )
      ),
      List(Json("id" := 1, "name" := "水樹奈々"))
    ),
    TestCase(
      "単純チェインのテーブルJOINでjsonオブジェクトを組み立てられる",
      JsObject(
        RootJoinDefinition(
          Table(
            "artist",
            JoinDefinition("id" -> "Int", false, "artist_id" -> "Int", Table("artist_kana"))
          )
        ).some,
        Map[String, JsValue](
          "name" -> JsString("artist", "name"),
          "kana" -> JsString("artist_kana", "kana")
        )
      ),
      List(Json("name" := "水樹奈々", "kana" := "みずきなな"))
    ),
    TestCase(
      "単純にjsonオブジェクトがネストしていても組み立てられる",
      JsObject(
        RootJoinDefinition(Table("artist")).some,
        Map[String, JsValue](
          "name" -> JsString("artist", "name"),
          "nest" -> JsObject(
            None,
            Map[String, JsValue](
              "name" -> JsString("artist", "name")
            )
          )
        )
      ),
      List(Json(
        "name" := "水樹奈々",
        "nest" := Json("name" := "水樹奈々")
      ))
    ),
    TestCase(
      "ネストと直列JOINが両方あっても組み立てられる",
      JsObject(
        RootJoinDefinition(
          Table(
            "artist",
            JoinDefinition("id" -> "Int", false, "artist_id" -> "Int", Table("artist_kana"))
          )
        ).some,
        Map[String, JsValue](
          "name" -> JsString("artist", "name"),
          "kana" -> JsString("artist_kana", "kana"),
          "musics" -> JsArray(
            JoinDefinition("id" -> "Int", true, "artist_id" -> "Int",
              Table("music")
            ).some,
            JsString("music", "name")
          )
        )
      ),
      List(Json(
        "name" := "水樹奈々",
        "kana" := "みずきなな",
        "musics" := Json.array(jString("深愛"), jString("innocent starter"))
      ))
    ),
    TestCase(
      "jsonオブジェクトが配列の中だとしても組み立てられる",
      JsObject(
        RootJoinDefinition(Table("artist")).some,
        Map[String, JsValue](
          "name" -> JsString("artist", "name"),
          "musics" -> JsArray(
            JoinDefinition("id" -> "Int", true, "artist_id" -> "Int",
              Table("music")
            ).some,
            JsObject(None, Map[String, JsValue]("name" -> JsString("music", "name")))
          )
        )
      ),
      List(Json(
        "name" := "水樹奈々",
        "musics" := Json.array(Json("name" := "深愛"), Json("name" := "innocent starter"))
      ))
    ),
    TestCase(
      "ネスト内からネスト外のカラムを参照できる",
      JsObject(
        RootJoinDefinition(Table("artist")).some,
        Map[String, JsValue](
          "name" -> JsString("artist", "name"),
          "musics" -> JsArray(
            JoinDefinition("id" -> "Int", true, "artist_id" -> "Int",
              Table("music")
            ).some,
            JsObject(None, Map[String, JsValue](
              "name" -> JsString("music", "name"),
              "artist_id" -> JsInt("artist", "id")
            ))
          )
        )
      ),
      List(Json(
        "name" := "水樹奈々",
        "musics" := Json.array(
          Json("name" := "深愛", "artist_id" := 1),
          Json("name" := "innocent starter", "artist_id" := 1)
        )
      ))
    ),
    TestCase(
      "ネストが二重でも組み立てられる",
      JsObject(
        RootJoinDefinition(Table("artist")).some,
        Map[String, JsValue](
          "name" -> JsString("artist", "name"),
          "musics" -> JsArray(
            JoinDefinition("id" -> "Int", true, "artist_id" -> "Int",
              Table("music")
            ).some,
            JsObject(
              None,
              Map[String, JsValue](
                "name" -> JsString("music", "name"),
                "contents" -> JsArray(
                  JoinDefinition("id" -> "Int", true, "music_id" -> "Int",
                    Table("content")
                  ).some,
                  JsObject(
                    None,
                    Map[String, JsValue](
                      "name" -> JsString("content", "name")
                    )
                  )
                )
              )
            )
          )
        )
      ),
      List(Json(
        "name" := "水樹奈々",
        "musics" := Json.array(
          Json(
            "name" := "深愛",
            "contents" := Json.array(
              Json("name" := "深愛 - ショートVer."),
              Json("name" := "深愛 - ロングVer.")
            )
          ),
          Json(
            "name" := "innocent starter",
            "contents" := Json.array(
              Json("name" := "innocent starter(inst)")
            )
          )
        )
      ))
    )
  )

  for (test <- tests) {
    it(test.title) {
      fetchJsonResult(test.input) should equal(test.expected)
    }
  }

  // TODO: ↓でエラーが起こるので、そのエラーハンドリングをもっとわかりやすくする
  // 次やる作業: Tableクラスが定義・編集中両方で兼任する必要がないから分離してjsonDefinitionsをvarじゃなくする
  JsObject(
    RootJoinDefinition(Table("artist")).some,
    Map[String, JsValue](
      "name" -> JsString("artist", "name"),
      "musics" -> JsArray(
        JoinDefinition("id" -> "Int", true, "artist_id" -> "Int",
          Table("music")
        ).some,
        JsInt("artist", "id")
      )
    )
  )

  it("テーブル構造が破綻していると例外が出る") {
    val jsValue = JsObject(
      RootJoinDefinition(Table("artist")).some,
      Map[String, JsValue](
        "name" -> JsString("artist", "name"),
        "musics" -> JsArray(
          JoinDefinition("id" -> "Int", false, "artist_id" -> "Int",
            Table("music")
          ).some,
          JsObject(None, Map[String, JsValue]("name" -> JsString("music", "name")))
        )
      )
    ) // TODO: 例外条件を絞ってこっちが意図して例外を出すようにする
    a[Exception] should be thrownBy {
      fetchJsonResult(jsValue)
    }
  }

  // https://dev.mysql.com/doc/refman/5.6/ja/group-by-functions.html#function_group-concat
  // TODO: group_concatの最大長について修正するようにしろ、ドキュメントでもいいけど
  // こういうTableJoin構造が間違っている奴、処理中に間違っていると出すようにする
  // (sql吐いて実行したあとにエラーを出す)
  // あとjsArray使っているのにJsonDefinitionの2番めがtrueじゃない奴とかも事前チェックでエラーにする
  // Optionで型を指定しているようなデータ構造はきっちりNoneを渡した時のテストも書いておくこと
}