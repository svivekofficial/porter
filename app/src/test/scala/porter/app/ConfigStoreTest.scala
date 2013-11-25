package porter.app

import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers
import porter.model.{Ident, Realm, Secret}
import com.typesafe.config.ConfigFactory

/**
 *
 * @since 25.11.13 12:53
 *
 */
class ConfigStoreTest extends FunSuite with ShouldMatchers {

  val testpw = Secret.bcryptPassword("test")

  test("read one realm with one account and two groups") {
    val cstr =
      s"""
        |realm1: {
        |   name: "my great realm"
        |   groups: {
        |      admin: {
        |        rules: [ "bla.bla", "!bla.bla.blop" ]
        |        props: {
        |          key: "value"
        |        }
        |      },
        |      users: {
        |        rules: [ "resource:read:/**" ]
        |      }
        |    }
        |    accounts: {
        |      john: {
        |        secret: "${testpw.asString}"
        |        groups: [ "admin", "users" ]
        |        props: {
        |          enabled: "true"
        |        }
        |      }
        |    }
        | }
      """.stripMargin
    val cfg = ConfigFactory.parseString(cstr)
    val store = new ConfigStore(cfg)
    store.realms should have size 1
    store.realms(0) should be (Realm("realm1", "my great realm"))
    store.groups should have size 2
    val groups = store.groups.map({ case(r,g) => g})
    groups(0).name should (be (Ident("admin")) or be (Ident("users")))
    groups(1).name should (be (Ident("admin")) or be (Ident("users")))

    val admin = if (groups(0).name.is("admin")) groups(0) else groups(1)
    val users = if (groups(0).name.is("users")) groups(0) else groups(1)
    admin.name should be (Ident("admin"))
    users.name should be (Ident("users"))
    admin.props should be (Map("key" -> "value"))
    users.props should have size 0
    admin.rules should be (Set("bla.bla", "!bla.bla.blop"))
    users.rules should be (Set("resource:read:/**"))

    store.accounts should have size 1
    val john = store.accounts(0)._2
    john.name should be (Ident("john"))
    john.secrets should be (Seq(testpw))
    john.groups should be (Set("users", "admin").map(Ident.apply))
    john.props should be (Map("enabled" -> "true"))
  }
}
