package com.prisma.deploy.specutils

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.prisma.ConnectorAwareTest
import com.prisma.deploy.connector.jdbc.database.JdbcDeployMutactionExecutor
import com.prisma.deploy.connector.mysql.MySqlDeployConnector
import com.prisma.deploy.connector.{DatabaseSchema, EmptyDatabaseIntrospectionInferrer, FieldRequirementsInterface}
import com.prisma.deploy.connector.postgres.PostgresDeployConnector
import com.prisma.deploy.migration.SchemaMapper
import com.prisma.deploy.migration.inference.{MigrationStepsInferrer, SchemaInferrer}
import com.prisma.deploy.migration.validation.DeployError
import com.prisma.deploy.schema.mutations._
import com.prisma.shared.models.ConnectorCapability.MigrationsCapability
import com.prisma.shared.models._
import com.prisma.utils.await.AwaitUtils
import com.prisma.utils.json.PlayJsonExtensions
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}
import play.api.libs.json.JsString

import scala.collection.mutable.ArrayBuffer

trait DeploySpecBase extends ConnectorAwareTest with BeforeAndAfterEach with BeforeAndAfterAll with AwaitUtils with PlayJsonExtensions {
  self: Suite =>

  implicit lazy val system                                   = ActorSystem()
  implicit lazy val materializer                             = ActorMaterializer()
  implicit lazy val testDependencies: TestDeployDependencies = TestDeployDependencies()
  implicit lazy val implicitSuite                            = self
  implicit lazy val deployConnector                          = testDependencies.deployConnector

  val server            = DeployTestServer()
  val projectsToCleanUp = new ArrayBuffer[String]

  override def prismaConfig = testDependencies.config
  def capabilities          = deployConnector.capabilities

  val basicTypesGql =
    """
      |type TestModel {
      |  id: ID! @unique
      |}
    """.stripMargin

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    deployConnector.initialize().await()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    deployConnector.shutdown().await()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    deployConnector.reset().await
  }

  def setupProject(
      schema: String,
      stage: String = "default",
      secrets: Vector[String] = Vector.empty
  )(implicit suite: Suite): (Project, Migration) = {
    val name       = suite.getClass.getSimpleName
    val idAsString = testDependencies.projectIdEncoder.toEncodedString(name, stage)
    deployConnector.deleteProjectDatabase(idAsString).await()
    server.addProject(name, stage)
    server.deploySchema(name, stage, schema.stripMargin, secrets)
  }

  def formatSchema(schema: String): String = JsString(schema).toString()
  def escapeString(str: String): String    = JsString(str).toString()
}

trait ActiveDeploySpecBase extends DeploySpecBase { self: Suite =>
  override def runOnlyForCapabilities = Set(MigrationsCapability)
}

trait PassiveDeploySpecBase extends DeploySpecBase with DataModelV2Base { self: Suite =>
  private val stageSeparator           = this.deployConnector.projectIdEncoder.stageSeparator
  val projectName                      = this.getClass.getSimpleName
  val projectStage                     = "default"
  val projectId                        = s"$projectName$stageSeparator$projectStage"
  override def doNotRunForCapabilities = Set(MigrationsCapability)
  lazy val slickDatabase               = deployConnector.deployMutactionExecutor.asInstanceOf[JdbcDeployMutactionExecutor].slickDatabase

  case class SQLs(postgres: String, mysql: String)

  def addProject() = {
    deployConnector.deleteProjectDatabase(projectId).await()
    server.addProject(projectName, projectStage)
  }

  def setup(sqls: SQLs): DatabaseSchema = {
    deployConnector.deleteProjectDatabase(projectId).await()
    if (slickDatabase.isMySql) {
      setupWithRawSQL(sqls.mysql)
    } else if (slickDatabase.isPostgres) {
      setupWithRawSQL(sqls.postgres)
    } else {
      sys.error("This is neither Postgres nor MySQL")
    }
    inspect
  }

  def setupWithRawSQL(sql: String)(implicit suite: Suite): Unit = {
    setupProjectDatabaseForProject(projectId, projectName, projectStage, sql)
  }

  private def setupProjectDatabaseForProject(schemaName: String, name: String, stage: String, sql: String): Unit = {
    val (session, isPostgres) = deployConnector match {
      case c: PostgresDeployConnector => (c.managementDatabase.createSession(), true)
      case c: MySqlDeployConnector    => (c.managementDatabase.database.createSession(), false)
      case x                          => sys.error(s"$x is not supported here")
    }
    val statement = session.createStatement()

    val dropSchema = if (isPostgres) {
      s"""drop schema if exists "$schemaName" cascade;"""
    } else {
      s"""drop database if exists `$schemaName`;"""
    }

    statement.execute(dropSchema)
    addProject()

    val createSchema = if (isPostgres) s"""create schema if not exists "$schemaName";""" else s"create database if not exists `$schemaName`;"
    statement.execute(createSchema)

    val setDefaultSchema = if (isPostgres) s"""SET search_path TO "$schemaName";""" else s"USE `$schemaName`;"
    statement.execute(setDefaultSchema)
    sql.split(';').foreach { sql =>
      val isNotOnlyWhiteSpace = sql.exists(c => c != '\n' && c != ' ')
      if (isNotOnlyWhiteSpace) {
        statement.execute(sql)
      }
    }
    session.close()
  }

  def executeSql(sqls: SQLs): DatabaseSchema = {
    if (slickDatabase.isMySql) {
      executeSql(sqls.mysql)
    } else if (slickDatabase.isPostgres) {
      executeSql(sqls.postgres)
    } else {
      sys.error("This is neither Postgres nor MySQL")
    }
    inspect
  }

  private def executeSql(sql: String*): Unit = {
    val session = deployConnector match {
      case c: PostgresDeployConnector => c.managementDatabase.createSession()
      case c: MySqlDeployConnector    => c.managementDatabase.database.createSession()
      case x                          => sys.error(s"$x is not supported here")
    }
    val statement        = session.createStatement()
    val setDefaultSchema = if (slickDatabase.isPostgres) s"""SET search_path TO "$projectId";""" else s"USE `$projectId`;"
    statement.execute(setDefaultSchema)
    sql.foreach(statement.execute)
    session.close()
  }
}

trait DataModelV2Base { self: PassiveDeploySpecBase =>
  import scala.concurrent.ExecutionContext.Implicits.global

  val project = Project(id = projectId, schema = Schema.empty)

  def deployThatMustError(
      dataModel: String,
      capabilities: ConnectorCapabilities = ConnectorCapabilities.empty,
      noMigration: Boolean = false
  ): Vector[DeployError] = {
    val result = deployInternal(dataModel, capabilities, noMigration)
    if (result.errors.nonEmpty) {
      result.errors.toVector
    } else {
      sys.error(s"The deploy did not return any error which is unexpected.")
    }
  }

  def deploy(
      dataModel: String,
      capabilities: ConnectorCapabilities = ConnectorCapabilities.empty,
      noMigration: Boolean = false
  ): DatabaseSchema = {
    val result = deployInternal(dataModel, capabilities, noMigration)
    if (result.errors.nonEmpty) {
      sys.error(s"Deploy returned unexpected errors: ${result.errors}")
    } else {
      inspect
    }
  }

  private def deployInternal(
      dataModel: String,
      capabilities: ConnectorCapabilities,
      noMigration: Boolean
  ): DeployMutationPayload = {
    val input = DeployMutationInput(
      clientMutationId = None,
      name = projectName,
      stage = projectStage,
      types = dataModel,
      dryRun = None,
      force = Some(true),
      secrets = Vector.empty,
      functions = Vector.empty,
      noMigration = Some(noMigration)
    )
    val refreshedProject = testDependencies.projectPersistence.load(projectId).await.getOrElse(sys.error(s"No project found for id $projectId"))
    val mutation = DeployMutation(
      args = input,
      project = refreshedProject,
      schemaInferrer = SchemaInferrer(capabilities),
      migrationStepsInferrer = MigrationStepsInferrer(),
      schemaMapper = SchemaMapper,
      migrationPersistence = testDependencies.migrationPersistence,
      projectPersistence = testDependencies.projectPersistence,
      migrator = testDependencies.migrator,
      functionValidator = testDependencies.functionValidator,
      invalidationPublisher = testDependencies.invalidationPublisher,
      capabilities = capabilities,
      clientDbQueries = deployConnector.clientDBQueries(project),
      databaseIntrospectionInferrer = EmptyDatabaseIntrospectionInferrer,
      fieldRequirements = FieldRequirementsInterface.empty,
      isActive = true,
      deployConnector = deployConnector
    )

    val result = mutation.execute.await
    result match {
      case MutationSuccess(result) =>
        result
      case MutationError =>
        sys.error("Deploy returned an unexpected error")
    }
  }

  def inspect: DatabaseSchema = {
    deployConnector.databaseInspector.inspect(projectId).await()
  }
}
