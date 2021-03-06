package com.prisma.api.mutations.embedded.nestedMutations.nonEmbeddedToEmbedded

import com.prisma.api.ApiSpecBase
import com.prisma.api.mutations.nonEmbedded.nestedMutations.SchemaBase
import com.prisma.shared.models.ConnectorCapability.EmbeddedTypesCapability
import com.prisma.shared.schema_dsl.SchemaDsl
import org.scalatest.{FlatSpec, Matchers}

class EmbeddedNestedCreateMutationInsideUpdateSpec extends FlatSpec with Matchers with ApiSpecBase with SchemaBase {
  override def runOnlyForCapabilities = Set(EmbeddedTypesCapability)

  "a P1! to C1! relation" should "error since old required parent relation would be broken" in {
    val project = SchemaDsl.fromString() { schemaP1reqToC1req }
    database.setup(project)

    val res = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p1"
          |    childReq: {
          |      create: {c: "c1"}
          |    }
          |  }){
          |    id
          |    childReq{
          |       id
          |    }
          |  }
          |}""".stripMargin,
        project
      )

    val parentId = res.pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    server.queryThatMustFail(
      s"""
         |mutation {
         |  updateParent(
         |  where: {id: "$parentId"}
         |  data:{
         |    p: "p2"
         |    childReq: {create: {c: "SomeC"}}
         |  }){
         |  p
         |  childReq {
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project,
      errorCode = 3042,
      errorContains = "The change you are trying to make would violate the required relation 'ChildToParent' between Child and Parent"
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a P1! to C1 relation" should "work" in {
    val project = SchemaDsl.fromString() { schemaP1reqToC1opt }
    database.setup(project)

    val res = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p1"
          |    childReq: {
          |      create: {c: "c1"}
          |    }
          |  }){
          |  id
          |    childReq{
          |       id
          |    }
          |  }
          |}""".stripMargin,
        project
      )

    val parentId = res.pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res2 = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {id: "$parentId"}
         |  data:{
         |    p: "p2"
         |    childReq: {create: {c: "SomeC"}}
         |  }){
         |    childReq {
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project
    )

    res2.toString should be("""{"data":{"updateParent":{"childReq":{"c":"SomeC"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a P1 to C1  relation " should "work" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1opt }
    database.setup(project)

    val res = server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p1"
          |    childOpt: {
          |      create: {c: "c1"}
          |    }
          |  }){
          |    id
          |    childOpt{
          |       id
          |    }
          |  }
          |}""".stripMargin,
        project
      )

    val parentId = res.pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res2 = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where:{id: "$parentId"}
         |  data:{
         |    p: "p2"
         |    childOpt: {create: {c: "SomeC"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project
    )

    res2.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"SomeC"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a P1 to C1  relation with the parent without a relation" should "work" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1opt }
    database.setup(project)

    val parent1Id = server
      .query(
        """mutation {
          |  createParent(data: {p: "p1"})
          |  {
          |    id
          |  }
          |}""".stripMargin,
        project
      )
      .pathAsString("data.createParent.id")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(0) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where:{id: "$parent1Id"}
         |  data:{
         |    p: "p2"
         |    childOpt: {create: {c: "SomeC"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"SomeC"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a PM to C1!  relation with a child already in a relation" should "work" in {
    val project = SchemaDsl.fromString() { schemaPMToC1req }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childrenOpt: {
        |      create: {c: "c1"}
        |    }
        |  }){
        |    childrenOpt{
        |       c
        |    }
        |  }
        |}""".stripMargin,
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |    where: {p: "p1"}
         |    data:{
         |    childrenOpt: {create: {c: "c2"}}
         |  }){
         |    childrenOpt {
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childrenOpt":[{"c":"c1"},{"c":"c2"}]}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }
  }

  "a P1 to C1!  relation with the parent and a child already in a relation" should "error in a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1req }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childOpt: {
        |      create: {c: "c1"}
        |    }
        |  }){
        |    childOpt{
        |       c
        |    }
        |  }
        |}""".stripMargin,
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    server.queryThatMustFail(
      s"""
         |mutation {
         |  updateParent(
         |  where: {p: "p1"}
         |  data:{
         |    childOpt: {create: {c: "c2"}}
         |  }){
         |    childOpt {
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project,
      errorCode = 3042,
      errorContains = "The change you are trying to make would violate the required relation 'ChildToParent' between Child and Parent"
    )
  }

  "a P1 to C1!  relation with the parent not already in a relation" should "work in a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaP1optToC1req }
    database.setup(project)

    server.query(
      """mutation {
          |  createParent(data: {
          |    p: "p1"
          |
          |  }){
          |    p
          |  }
          |}""".stripMargin,
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(0) }

    val res = server.query(
      s"""
           |mutation {
           |  updateParent(
           |  where: {p: "p1"}
           |  data:{
           |    childOpt: {create: {c: "c1"}}
           |  }){
           |    childOpt {
           |      c
           |    }
           |  }
           |}
      """.stripMargin,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"c1"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a PM to C1  relation with the parent already in a relation" should "work through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaPMToC1opt }
    database.setup(project)

    server
      .query(
        """mutation {
          |  createParent(data: {
          |    p: "p1"
          |    childrenOpt: {
          |      create: [{c: "c1"}, {c: "c2"}]
          |    }
          |  }){
          |    childrenOpt{
          |       c
          |    }
          |  }
          |}""".stripMargin,
        project
      )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: { p: "p1"}
         |  data:{
         |    childrenOpt: {create: [{c: "c3"}]}
         |  }){
         |    childrenOpt {
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childrenOpt":[{"c":"c1"},{"c":"c2"},{"c":"c3"}]}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(3) }
  }

  "a P1! to CM  relation with the parent already in a relation" should "work through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaP1reqToCM }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childReq: {
        |      create: {c: "c1"}
        |    }
        |  }){
        |    childReq{
        |       c
        |    }
        |  }
        |}""".stripMargin,
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: {p: "p1"}
         |  data:{
         |    childReq: {create: {c: "c2"}}
         |  }){
         |    childReq {
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childReq":{"c":"c2"}}}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a P1 to CM  relation with the child already in a relation" should "work through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaP1optToCM }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childOpt: {
        |      create: {c: "c1"}
        |    }
        |  }){
        |    childOpt{
        |       c
        |    }
        |  }
        |}""".stripMargin,
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |    where: {p: "p1"}
         |    data:{
         |    childOpt: {create: {c: "c2"}}
         |  }){
         |    childOpt{
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childOpt":{"c":"c2"}}}}""")

    server.query(s"""query{children{c, parentsOpt{p}}}""", project).toString should be(
      """{"data":{"children":[{"c":"c1","parentsOpt":[]},{"c":"c2","parentsOpt":[{"p":"p1"}]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(1) }
  }

  "a PM to CM  relation with the children already in a relation" should "be disconnectable through a nested mutation by unique" in {
    val project = SchemaDsl.fromString() { schemaPMToCM }
    database.setup(project)

    server.query(
      """mutation {
        |  createParent(data: {
        |    p: "p1"
        |    childrenOpt: {
        |      create: [{c: "c1"},{c: "c2"}]
        |    }
        |  }){
        |    childrenOpt{
        |       c
        |    }
        |  }
        |}""".stripMargin,
      project
    )

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(2) }

    val res = server.query(
      s"""
         |mutation {
         |  updateParent(
         |  where: { p: "p1"}
         |  data:{
         |    childrenOpt: {create: [{c: "c3"}]}
         |  }){
         |    childrenOpt{
         |      c
         |    }
         |  }
         |}
      """.stripMargin,
      project
    )

    res.toString should be("""{"data":{"updateParent":{"childrenOpt":[{"c":"c1"},{"c":"c2"},{"c":"c3"}]}}}""")

    server.query(s"""query{children{c, parentsOpt{p}}}""", project).toString should be(
      """{"data":{"children":[{"c":"c1","parentsOpt":[{"p":"p1"}]},{"c":"c2","parentsOpt":[{"p":"p1"}]},{"c":"c3","parentsOpt":[{"p":"p1"}]}]}}""")

    ifConnectorIsActive { dataResolver(project).countByTable("_ChildToParent").await should be(3) }
  }

  "a one to many relation" should "be creatable through a nested mutation" in {
    val project = SchemaDsl.fromString() {
      """type Comment{
        |   id: ID! @unique
        |   text: String
        |   todo: Todo
        |}
        |
        |type Todo{
        |   id: ID! @unique
        |   comments: [Comment]
        |}"""
    }

    database.setup(project)

    val createResult = server.query(
      """mutation {
        |  createTodo(data:{}){
        |    id
        |  }
        |}
      """.stripMargin,
      project
    )
    val id = createResult.pathAsString("data.createTodo.id")

    val result = server.query(
      s"""mutation {
        |  updateTodo(
        |    where: {
        |      id: "$id"
        |    }
        |    data:{
        |      comments: {
        |        create: [{text: "comment1"}, {text: "comment2"}]
        |      }
        |    }
        |  ){
        |    comments {
        |      text
        |    }
        |  }
        |}
      """.stripMargin,
      project
    )

    mustBeEqual(result.pathAsJsValue("data.updateTodo.comments").toString, """[{"text":"comment1"},{"text":"comment2"}]""")
  }

  "a many to one relation" should "be creatable through a nested mutation" in {
    val project = SchemaDsl.fromString() {
      """type Comment{
        |   id: ID! @unique
        |   text: String
        |   todo: Todo
        |}
        |
        |type Todo{
        |   id: ID! @unique
        |   title: String
        |   comments: [Comment]
        |}"""
    }

    database.setup(project)

    val createResult = server.query(
      """mutation {
        |  createComment(data:{}){
        |    id
        |  }
        |}
      """.stripMargin,
      project
    )
    val id = createResult.pathAsString("data.createComment.id")

    val result = server.query(
      s"""
        |mutation {
        |  updateComment(
        |    where: {
        |      id: "$id"
        |    }
        |    data: {
        |      todo: {
        |        create: {title: "todo1"}
        |      }
        |    }
        |  ){
        |    id
        |    todo {
        |      title
        |    }
        |  }
        |}
      """.stripMargin,
      project
    )
    mustBeEqual(result.pathAsString("data.updateComment.todo.title"), "todo1")
  }

  "a many to one relation" should "be creatable through a nested mutation using non-id unique field" in {
    val project = SchemaDsl.fromString() {
      """type Comment{
        |   id: ID! @unique
        |   text: String! @unique
        |   todo: Todo
        |}
        |
        |type Todo{
        |   id: ID! @unique
        |   title: String! @unique
        |   comments: [Comment]
        |}"""
    }

    database.setup(project)

    server.query(
      """mutation {
        |  createComment(data:{ text: "comment"}){
        |    id
        |    text
        |  }
        |}
      """.stripMargin,
      project
    )

    val result = server.query(
      s"""
         |mutation {
         |  updateComment(
         |    where: {
         |      text: "comment"
         |    }
         |    data: {
         |      todo: {
         |        create: {title: "todo1"}
         |      }
         |    }
         |  ){
         |    id
         |    todo {
         |      title
         |    }
         |  }
         |}
      """.stripMargin,
      project
    )
    mustBeEqual(result.pathAsString("data.updateComment.todo.title"), "todo1")
  }

  "Create in Update" should "add to toMany relations" in {

    val project = SchemaDsl.fromString() {
      """type Top {
        |   id: ID! @unique
        |   unique: Int! @unique
        |   name: String!
        |   middle: [Middle]
        |}
        |
        |type Middle @embedded {
        |   unique: Int! @unique
        |   name: String!
        | }"""
    }

    database.setup(project)

    val res = server.query(
      s"""mutation {
         |   createTop(data: {
         |   unique: 1,
         |   name: "Top",
         |   middle: {create:[{
         |      unique: 11,
         |      name: "Middle"
         |      },
         |      {
         |      unique: 12,
         |      name: "Middle2"
         |    }]
         |   }
         |}){
         |  unique,
         |  middle{
         |    unique
         |  }
         |}}""",
      project
    )

    res.toString should be("""{"data":{"createTop":{"unique":1,"middle":[{"unique":11},{"unique":12}]}}}""")

    val res2 = server.query(
      s"""mutation {
         |   updateTop(
         |   where:{unique: 1}
         |   data: {
         |      middle: {create:[{
         |          unique: 13,
         |          name: "Middle3"
         |          },
         |          {
         |          unique: 14,
         |          name: "Middle4"
         |        }]
         |   }
         |}){
         |  unique,
         |  middle{
         |    unique,
         |  }
         |}}""",
      project
    )

    res2.toString should be("""{"data":{"updateTop":{"unique":1,"middle":[{"unique":11},{"unique":12},{"unique":13},{"unique":14}]}}}""")

  }

  "Update with nested Create" should "work" in {

    val project = SchemaDsl.fromString() {
      """type Top {
        |   id: ID! @unique
        |   unique: Int! @unique
        |   name: String!
        |   middle: [Middle]
        |}
        |
        |type Middle @embedded {
        |   unique: Int! @unique
        |   name: String!
        |}"""
    }

    database.setup(project)

    server.query(
      s"""mutation {
         |   createTop(data: {
         |   unique: 1,
         |   name: "Top"
         |}){
         |  unique
         |}}""".stripMargin,
      project
    )

    val res = server.query(
      s"""mutation {
         |   updateTop(
         |   where:{unique:1}
         |   data: {
         |   name: "Top2",
         |   middle: {create:[
         |      {
         |      unique: 11,
         |      name: "Middle"
         |      },
         |      {
         |      unique: 12,
         |      name: "Middle2"
         |    }]
         |   }
         |}){
         |  unique,
         |  middle{
         |    unique
         |  }
         |}}""".stripMargin,
      project
    )

    res.toString should be("""{"data":{"updateTop":{"unique":1,"middle":[{"unique":11},{"unique":12}]}}}""")
  }

}
