package com.github.bigwheel.adenosyn.changerecorder

import com.github.bigwheel.adenosyn.sqlutil
import com.github.bigwheel.adenosyn.sqlutil._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite
import org.slf4j.LoggerFactory
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scalikejdbc._

trait DatabaseSpecHelper extends BeforeAndAfterAll { this: Suite =>

  protected[this] val postfix = "398"
  protected[this] val observeeDbName = "observee" + postfix
  protected[this] val recordDbName = "record" + postfix
  protected[this] val userName = "changerecorder" + postfix
  protected[this] val password = "cr" + postfix

  override protected def beforeAll() = {
    super.beforeAll()

    val l = LoggerFactory.getLogger(getClass)
    Process("docker-compose up -d").!(ProcessLogger(l.debug, l.warn))

    Class.forName("com.mysql.jdbc.Driver")
    ConnectionPool.singleton(sqlutil.url(), "root", "root")
    ConnectionPool.add('observee, sqlutil.url(observeeDbName), "root", "root")
    ConnectionPool.add('record, sqlutil.url(recordDbName), "root", "root")
  }

  protected[this] implicit val session = AutoSession

  protected[this] def withDatabases(test: => Any) {
    sqlutil.executeStatements(
      s"""DROP DATABASE IF EXISTS $observeeDbName;
         |CREATE DATABASE $observeeDbName;
         |DROP DATABASE IF EXISTS $recordDbName;
         |CREATE DATABASE $recordDbName;
         |DROP USER IF EXISTS '$userName'@'%';""".stripMargin
    )
    test
  }

  protected[this] def withUserAndDatabases(test: => Any) {
    withDatabases {
      sqlutil.executeStatements(
        s"""CREATE USER '$userName'@'%' IDENTIFIED BY '$password';
           |GRANT ALL ON $observeeDbName.* TO '$userName'@'%';
           |GRANT ALL ON $recordDbName.* TO '$userName'@'%';""".stripMargin
      )
      test
    }
  }

  protected[this] def withTableUserAndDatabases(test: => Any) {
    withUserAndDatabases {
      (s"CREATE TABLE $observeeDbName.table1(pr1 INTEGER not null," +
        "pr2 VARCHAR(30) not null, col1 INTEGER, PRIMARY KEY(pr1, pr2))").query
      test
    }
  }

}
