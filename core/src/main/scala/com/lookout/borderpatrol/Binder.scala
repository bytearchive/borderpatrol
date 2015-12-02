package com.lookout.borderpatrol

import java.net.URL

import com.twitter.finagle.{ChannelWriteException, Httpx, Service}
import com.twitter.finagle.httpx.{Response, Request}
import com.twitter.util.Future
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/**
 * Binder object defines methods and shells used to bind to upstream endpoints
 */
object Binder {

  /**
   * The trait for the context binding. It exposes common methods
   * to be made available from all the contexts.
   * @tparam A
   */
  trait BinderContext[A] {
    def name(manager: A): String
    def hosts(manager: A): Set[URL]
  }

  /**
   * Pod that contains the context (which we are trying to bind) and
   * the actual request to be processed and to be sent to endpoint
   */
  case class BindRequest[A: BinderContext](context: A, req: Request) {
    def name: String = implicitly[BinderContext[A]].name(context)
    def hosts: Set[URL] = implicitly[BinderContext[A]].hosts(context)
  }

  /**
   * It enables dynamic binding to the endpoints (e.g. login service, identity service, etc)
   *
   * Note that BinderContext makes it possible to templatize this code for all the LoginManagerBinder, ManagerBinder,
   * ServiceIdentifierBinder, etc, by making calls to methods (e.g. name & hosts) visible in the template.
   *
   * @param cache Caches the already established client service
   * @tparam A
   */
  abstract class MBinder[A: BinderContext](cache: mutable.Map[String, Service[Request, Response]] =
                                           mutable.Map.empty[String, Service[Request, Response]])
      extends Service[BindRequest[A], Response] {
    def apply(req: BindRequest[A]): Future[Response] =
      this.synchronized(cache.getOrElse(req.name, {

        // If its https, use TLS
        val https = !req.hosts.filter(u => u.getProtocol == "https").isEmpty
        val hostname = req.hosts.map(u => u.getHost).mkString

        // Find CSV of host & ports
        val hostAndPorts = req.hosts.map(u => u.getAuthority).mkString(",")
        util.Combinators.tap(
          if (https) Httpx.client.withTls(hostname).newService(hostAndPorts)
          else Httpx.newService(hostAndPorts)
        )(cli => cache(req.name) = cli)

      })).apply(req.req)

    def get(name: String): Option[Service[Request, Response]] = cache.get(name)
  }

  /**
   * implicit values for evidence parameter of type BinderContext
   */
  implicit object LoginManagerBinderContext extends BinderContext[LoginManager] {
    def name(lm: LoginManager): String = lm.name
    def hosts(lm: LoginManager): Set[URL] = lm.protoManager.hosts
  }
  implicit object ManagerBinderContext extends BinderContext[Manager] {
    def name(m: Manager): String = m.name
    def hosts(m: Manager): Set[URL] = m.hosts
  }
  implicit object ServiceIdentifierBinderContext extends BinderContext[ServiceIdentifier] {
    def name(sid: ServiceIdentifier): String = sid.name
    def hosts(sid: ServiceIdentifier): Set[URL] = sid.hosts
  }

  /**
   * Binder objects
   */
  case object LoginManagerBinder extends MBinder[LoginManager]
  case object ManagerBinder extends MBinder[Manager]
  case object ServiceIdentifierBinder extends MBinder[ServiceIdentifier]
}


object BinderBase {
  val cache: mutable.Map[String, Service[Request, Response]] =
    mutable.Map.empty[String, Service[Request, Response]]

  private[this] def client(name: String, urls: Set[URL]): Future[Service[Request, Response]] =
    this.synchronized {
      cache.getOrElse(name, {

        // If its https, use TLS
        val https = urls.filter(u => u.getProtocol == "https").nonEmpty
        val hostname = urls.map(u => u.getHost).mkString

        // Find CSV of host & ports
        val hostAndPorts = urls.map(u => u.getAuthority).mkString(",")
        util.Combinators.tap(
          if (https) Httpx.client.withTls(hostname).newService(hostAndPorts)
          else Httpx.newService(hostAndPorts)
        )(cli => cache(name) = cli)
      })
    }.toFuture

  def connect(name: String, urls: Set[URL], request: Request): Future[Response] = {
    (for {
      cl <- client(name, urls)
      res <- cl.apply(request)
    } yield res) handle {
      case e => throw CommunicationError(s"${name} with ${e.getMessage}")
    }
  }

  def get(name: String): Option[Service[Request, Response]] = cache.get(name)
}
