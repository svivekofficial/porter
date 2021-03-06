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

package porter.app.client

import porter.client.PorterClient
import akka.actor.{ActorSelection, ActorRef}
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import porter.app.akka.{PorterRef, PorterUtil}
import porter.auth.Decider
import porter.client.messages._
import porter.model.{PasswordCredentials, Ident}
import scala.concurrent.duration.FiniteDuration
import akka.util.Timeout
import porter.client.json.MessageJsonProtocol.UserPass
import porter.client.json.MessageJsonProtocol.SimpleAuthResult

/**
 * This class is framing the porter actor ref to provide a type safe view to it.
 *
 * @param porterRef
 * @param decider
 */
class PorterAkkaClient(val porterRef: PorterRef, val decider: Decider) extends PorterClient {
  import akka.pattern.ask

  private def exec[A, B: ClassTag] = new Command[A, B] {
    def apply(req: A)(implicit ec: ExecutionContext, timeout: FiniteDuration) = {
      implicit val to: Timeout = Timeout(timeout)
      (porterRef ? req).mapTo[B]
    }
  }

  def authenticate = exec[Authenticate, AuthenticateResp]
  def authenticateAccount = new AuthcAccountCmd {
    def apply(req: Authenticate)(implicit ec: ExecutionContext, timeout: FiniteDuration) = {
      implicit val to: Timeout = Timeout(timeout)
      PorterUtil.authenticateAccount(porterRef, req.realmId, req.creds, decider)
        .map(r => AuthAccount(success = true, Some(r._2)))
        .recover({ case x => AuthAccount(success = false, None) })
    }
  }
  def authenticateSimple(realm: Ident) = new AuthcSimpleCmd {
    def apply(req: UserPass)(implicit ec: ExecutionContext, timeout: FiniteDuration) = {
      implicit val to: Timeout = Timeout(timeout)
      authenticate(Authenticate(realm, Set(PasswordCredentials(req.account, req.password))))
        .map(r => SimpleAuthResult(r.result.map(decider) getOrElse false))
    }
  }

  def authorize = exec[Authorize, AuthorizeResp]
  def retrieveNonce = exec[RetrieveServerNonce, RetrieveServerNonceResp]


  def listAccounts = exec[GetAllAccounts, FindAccountsResp]
  def findAccounts = exec[FindAccounts, FindAccountsResp]
  def listGroups = exec[GetAllGroups, FindGroupsResp]
  def findGroups = exec[FindGroups, FindGroupsResp]
  def findRealms = exec[FindRealms, FindRealmsResp]

  def updateAccount = exec[UpdateAccount, OperationFinished]
  def createNewAccount = new UpdateAccountCmd {
    def apply(req: UpdateAccount)(implicit ec: ExecutionContext, timeout: FiniteDuration) = {
      implicit val to: Timeout = Timeout(timeout)
      PorterUtil.createNewAccount(porterRef, req.realmId, req.account)
        .map(_ => OperationFinished.success)
        .recover({ case x => OperationFinished.failure(x) })
    }
  }
  def updateGroup = exec[UpdateGroup, OperationFinished]
  def updateRealm = exec[UpdateRealm, OperationFinished]
  def deleteAccount = exec[DeleteAccount, OperationFinished]
  def deleteGroup = exec[DeleteGroup, OperationFinished]
  def deleteRealm = exec[DeleteRealm, OperationFinished]

  def changeSecrets = new ChangeSecretsCmd {
    def apply(req: ChangeSecrets)(implicit ec: ExecutionContext, timeout: FiniteDuration) = {
      implicit val to: Timeout = Timeout(timeout)
      PorterUtil.changePassword(porterRef, req.realm, req.current, req.secrets, decider)
        .map(_ => OperationFinished.success)
        .recover({ case x => OperationFinished.failure(x) })
    }
  }

  def updateAuthProps = exec[UpdateAuthProps, OperationFinished]
}
