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

package porter.app.akka.telnet

import scala.concurrent.{Future, ExecutionContext}
import akka.util.Timeout
import scala.util.Try
import porter.model._
import porter.app.akka.PorterUtil

object AccountCommands extends Commands {
  import akka.pattern.ask
  import porter.util._
  import porter.client.messages._

  def makeDoc =
    """
      |Account commands
      |----------------
      |update account           update an existing account or creates a new one
      |                         if no account with the given name exists
      |delete account <name>    delete an account with the given name
      |la [filter]              list all accounts; filter can be a glob like pattern, eg.
      |                         `la d*` or `la bl?b`
      |change pass              set a new (plain) password for an account
      |set pass                 sets a new crypted password for an account
      |add groups               add new groups to an account
      |remove groups            remove groups from an account
    """.stripMargin

  def make(implicit executor: ExecutionContext, to: Timeout) =
    List(update, listall, delete, changePassword, setPassword,
      manageGroups("add", (a, b) => a++b), manageGroups("remove", (a, b) => a -- b))

  def update(implicit executor: ExecutionContext, to: Timeout): Command = new Form {
    def fields = AccountDetails.all

    def validateConvert = {
      case (key, value) if key == AccountDetails.name =>
        Try(Ident(value))
      case (key, value) if key == AccountDetails.groups =>
        Try { makeList(' ')(value).map(s => Ident(s.trim)).toSet }
      case (key, value) if key == AccountDetails.props =>
        makePairs(value)
    }

    def show = {
      case in @ Input(msg, conn, porter, sess) if msg == "update account" =>
        in.withRealm { r =>
          conn ! tcp("Enter the account details.\n")
        }
        sess.realm.isDefined
    }

    def onComplete(in: Input) = {
      val ad = AccountDetails.toAccount(in.session)
      val result = for {
        r <- in.realmFuture
        op <- (in.porter ? UpdateAccount(r.id, ad.get)).mapTo[OperationFinished]
      } yield "Account updated: " + op.success
      in << result
    }
  }

  def listall(implicit executor: ExecutionContext, to: Timeout): Command = {
    case in @Input(msg, conn, porter, _) if msg startsWith "la" =>
      val filter = if (msg == "la") (s: String) => true else (s: String) => Glob.matches(msg.substring(3), s)
      val f = for {
        r <- in.realmFuture
        list <- (porter ? GetAllAccounts(r.id)).mapTo[FindAccountsResp].map(_.accounts)
      } yield {
        val groups = (a: Account) => a.groups.map(_.name).mkString("(", ",", ")")
        val props = (a: Account) => propsToString(a.props, "   ", "\n   ", "")
        for (a <- list; if filter(a.name.name)) {
          in <~ s"${a.name.name} ${groups(a)}\n${props(a)}\n"
        }
        ""
      }
      in << f
  }

  def delete(implicit executor: ExecutionContext, to: Timeout): Command = {
    case in@Input(msg, conn, porter, _) if msg.startsWith("delete account") =>
      val result = for {
        realm <- in.realmFuture
        id <- Future.immediate(Ident(msg.substring("delete account".length).trim))
        op <- (porter ? DeleteAccount(realm.id, id)).mapTo[OperationFinished]
      } yield "Account deleted: "+ op.success
      in << result
  }

  def changePassword(implicit ec: ExecutionContext, to: Timeout): Command = new Form {
    def fields = List("login", "password", "password-crypt")

    def show = {
      case in@Input(msg, conn, _, sess) if msg == "change pass" =>
        in.withRealm { r =>
          conn ! tcp("Enter the login name and a new password. Leave password-crypt empty if unsure.\n")
        }
        sess.realm.isDefined
    }

    def validateConvert = {
      case (key, value) if key == "login" => Try(Ident(value))
      case (key, value) if key == "password-crypt" => Try(PasswordCrypt(value).get)
    }

    def onComplete(in: Input) = {
      val login = in.session[Ident]("login")
      val passw = in.session[String]("password")
      val crypt = in.session[PasswordCrypt]("password-crypt")
      in.withRealm { realm =>
        val f = PorterUtil.updateAccount(in.porter, realm.id, login, _.changeSecret(Password(crypt)(passw)))
        in << f.map(or => s"Password changed: ${or.success}")
      }
    }
  }

  def setPassword(implicit ec: ExecutionContext, to: Timeout): Command = new Form {
    def fields = List("login", "password")

    def show = {
      case in@Input(msg, conn, _, sess) if msg == "set pass" =>
        in.withRealm { r =>
          conn ! tcp("Enter the login name and the crypted password.\n")
        }
        sess.realm.isDefined
    }

    def validateConvert = {
      case (key, value) if key == "login" => Try(Ident(value))
    }

    def onComplete(in: Input) = {
      val login = in.session[Ident]("login")
      val passw = in.session[String]("password")
      in.withRealm { realm =>
        val f = PorterUtil.updateAccount(in.porter, realm.id, login, _.changeSecret(Password.crypted(passw)))
        in << f.map(or => s"Password set: ${or.success}")
      }
    }
  }

  def manageGroups(verb: String, alter: (Set[Ident], Set[Ident]) => Set[Ident])(implicit ec: ExecutionContext, to: Timeout) = new Form {
    def fields = List("login", "groups")
    def show = {
      case in@Input(msg, conn, _, sess) if msg == (verb+" groups") =>
        in.withRealm { _ =>
          conn ! tcp("Please enter login and groups (separated by space) to add.\n")
        }
        sess.realm.isDefined
    }
    def validateConvert = {
      case ("login", value) => Try(Ident(value))
      case ("groups", value) => Try(makeList(' ')(value).map(_.trim).toSet.map(Ident.apply))
    }
    def onComplete(in: Input) = {
      val login = in.session[Ident]("login")
      val toadd = in.session[Set[Ident]]("groups")
      in.withRealm { realm =>
        val change: Account => Account = _.updatedGroups(g => alter(g, toadd))
        val f = PorterUtil.updateAccount(in.porter, realm.id, login, change)
        in << f.map(or => if (or.success) "Success" else "Failed")
      }
    }
  }

  object AccountDetails {
    val name = "name"
    val groups = "groups"
    val password = "password"
    val props = "props"
    val all = List(name, groups, password, props)

    def toAccount(sess: Session) = Try(Account(
      sess[Ident](name),
      sess[Properties](props),
      sess[Set[Ident]](groups),
      Seq(Password(sess[String](password)))
    ))
  }
}
