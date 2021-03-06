/*
 * Copyright 2014 porter <https://github.com/eikek/porter>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package porter.store

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import porter.model._
import porter.model.PropertyList.mutableSource

class PropertiesStoreTest extends FunSuite with ShouldMatchers {

  val testPw = Password("test")
  val readOnly = mutableSource.toFalse
  val acc = Account("john", readOnly(Map("email" -> "john@mail.com", "enabled" -> "true")), Set("users", "admin"), Seq(testPw))

  private def createProps() = {
    val props = new java.util.Properties()
    props.setProperty("porter.realms", "app1")
    props.setProperty("porter.app1.name", "My Realm")
    props.setProperty("porter.app1.accounts", "john")
    props.setProperty("porter.app1.account.john.secret", testPw.asString)
    props.setProperty("porter.app1.account.john.groups", "admin, users")
    props.setProperty("porter.app1.account.john.props", "email -> john@mail.com, enabled->true")
    props.setProperty("porter.app1.groups", " admin,  users ")
    props.setProperty("porter.app1.group.admin.rules", "resource:read:/main/**, base:manage")
    props
  }

  private def createProps(values: Map[String, String]) = {
    val props = new java.util.Properties()
    for ((k, v) <- values) props.setProperty(k, v)
    props
  }

  test("list realms") {
    val store = PropertiesStore(createProps(Map(
      "porter.realms" -> "app1,app2,app3",
      "porter.app1.name" -> "A realm 1",
      "porter.app2.name" -> "A realm 2",
      "porter.app3.name" -> "A realm 3",
      "porter.app4.name" -> "A realm 4"
    )))
    Await.result(store.allRealms, 5.seconds) should be (List(Realm("app1", "A realm 1"),
      Realm("app2", "A realm 2"), Realm("app3", "A realm 3")))
  }

  test("find realms") {
    val store = PropertiesStore(createProps())
    Await.result(store.findRealms(Set("app1")), 5.seconds) should be (List(Realm("app1", "My Realm")))
    Await.result(store.findRealms(Set("asdasd")), 5.seconds) should be (List())
  }

  test("list groups") {
    val store = PropertiesStore(createProps())
    Await.result(store.allGroups("app1"), 5.seconds) should be (List(
      Group("admin", readOnly(Map.empty), Set("resource:read:/main/**", "base:manage")), Group("users", readOnly(Map.empty))))
  }

  test ("find groups") {
    val store = PropertiesStore(createProps())
    Await.result(store.findGroups("app1", Set("users")), 5.seconds) should be (List(Group("users", readOnly(Map.empty))))
  }

  test ("list accounts") {
    val store = PropertiesStore(createProps())
    Await.result(store.allAccounts("app1"), 5.seconds) should be (List(acc))
  }

  test ("find accounts") {
    val store = PropertiesStore(createProps())
    Await.result(store.findAccounts("app1", Set("john")), 5.seconds) should be (List(acc))
  }

  test ("find accounts with credentials") {
    val store = PropertiesStore(createProps())
    val creds: Credentials = PasswordCredentials("john", "bla")
    Await.result(store.findAccountsFor("app1", Set(creds)), 5.seconds) should be (List(acc))
  }
}
