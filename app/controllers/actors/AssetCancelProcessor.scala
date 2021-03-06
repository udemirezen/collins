package controllers
package actors

import scala.concurrent.duration._
import models.{Asset, AssetLifecycle, MetaWrapper, Model, Status => AStatus}
import play.api.mvc.{AnyContent, Request}
import util.plugins.SoftLayer
import util.concurrent.BackgroundProcess
import com.twitter.util.Await

case class AssetCancelProcessor(tag: String, userTimeout: Option[FiniteDuration] = None)(implicit req: Request[AnyContent])
  extends BackgroundProcess[Either[ResponseData,Long]]
{
  override def defaultTimeout = 10 seconds

  val timeout = userTimeout.getOrElse(defaultTimeout)
  def run(): Either[ResponseData,Long] = {
    req.body.asFormUrlEncoded.flatMap(_.get("reason")).flatMap(_.headOption).map(_.trim).filter(_.size > 0).map { _reason =>
      val reason = _reason.trim
      Api.withAssetFromTag(tag) { asset =>
        SoftLayer.pluginEnabled.map { plugin =>
          plugin.softLayerId(asset) match {
            case None =>
              Left(Api.getErrorMessage("Asset is not a softlayer asset"))
            case Some(n) =>
              Await.result(plugin.cancelServer(n, reason)) match {
                case 0L =>
                  Left(Api.getErrorMessage("There was an error cancelling this server"))
                case ticketId =>
                  Asset.inTransaction {
                    MetaWrapper.createMeta(asset, Map("CANCEL_TICKET" -> ticketId.toString))
                    AssetLifecycle.updateAssetStatus(asset, AStatus.Cancelled, None, reason)
                  }
                  Await.result(plugin.setNote(n, "Cancelled: %s".format(reason)))
                  Right(ticketId)
              }
          }
        }.getOrElse {
          Left(Api.getErrorMessage("SoftLayer plugin is not enabled"))
        }
      }
    }.getOrElse(Left(Api.getErrorMessage("No reason specified for cancellation")))
  }
}
