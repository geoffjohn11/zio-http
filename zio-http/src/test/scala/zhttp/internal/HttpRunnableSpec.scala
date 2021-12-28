package zhttp.internal

import sttp.client3.asynchttpclient.zio.{SttpClient, send}
import sttp.client3.{Response => SResponse, UriContext, asWebSocketUnsafe, basicRequest}
import sttp.model.{Header => SHeader}
import sttp.ws.WebSocket
import zhttp.http.URL.Location
import zhttp.http._
import zhttp.internal.DynamicServer.HttpEnv
import zhttp.service._
import zhttp.service.client.ClientSSLHandler.ClientSSLOptions
import zio.test.DefaultRunnableSpec
import zio.{Chunk, Has, Task, ZIO, ZManaged}

/**
 * Should be used only when e2e tests needs to be written which is typically for logic that is part of the netty based
 * backend. For most of the other use cases directly running the HttpApp should suffice. HttpRunnableSpec spins of an
 * actual Http server and makes requests.
 */
abstract class HttpRunnableSpec extends DefaultRunnableSpec { self =>
  def serve[R <: Has[_]](
    app: HttpApp[R, Throwable],
  ): ZManaged[R with EventLoopGroup with ServerChannelFactory with DynamicServer, Nothing, Unit] =
    for {
      start <- Server.make(Server.app(app) ++ Server.port(0) ++ Server.paranoidLeakDetection).orDie
      _     <- ZIO.accessM[DynamicServer](_.get.setPort(start.port)).toManaged_
    } yield ()

  def request(
    path: Path = !!,
    method: Method = Method.GET,
    content: String = "",
    headers: Headers = Headers.empty,
  ): ZIO[EventLoopGroup with ChannelFactory with DynamicServer, Throwable, Client.ClientResponse] = {
    for {
      port <- DynamicServer.getPort
      data = HttpData.fromString(content)
      response <- Client.request(
        Client.ClientParams(method -> URL(path, Location.Absolute(Scheme.HTTP, "localhost", port)), headers, data),
        ClientSSLOptions.DefaultSSL,
      )
    } yield response
  }

  def status(path: Path): ZIO[EventLoopGroup with ChannelFactory with DynamicServer, Throwable, Status] = {
    for {
      port   <- DynamicServer.getPort
      status <- Client
        .request(
          Method.GET -> URL(path, Location.Absolute(Scheme.HTTP, "localhost", port)),
          ClientSSLOptions.DefaultSSL,
        )
        .map(_.status)
    } yield status
  }

  def webSocketRequest(
    path: Path = !!,
    headers: Headers = Headers.empty,
  ): ZIO[SttpClient with DynamicServer, Throwable, SResponse[Either[String, WebSocket[Task]]]] = {
    // todo: uri should be created by using URL().asString but currently support for ws Scheme is missing
    for {
      port <- DynamicServer.getPort
      url                       = s"ws://localhost:$port${path.asString}"
      headerConv: List[SHeader] = headers.toList.map(h => SHeader(h._1.toString(), h._2.toString()))
      res <- send(basicRequest.get(uri"$url").copy(headers = headerConv).response(asWebSocketUnsafe))
    } yield res
  }

  implicit class RunnableHttpAppSyntax(app: HttpApp[HttpEnv, Throwable]) {
    def deploy: ZIO[DynamicServer, Nothing, String] = DynamicServer.deploy(app)

    def request(
      path: Path = !!,
      method: Method = Method.GET,
      content: String = "",
      headers: Headers = Headers.empty,
    ): ZIO[EventLoopGroup with ChannelFactory with DynamicServer, Throwable, Client.ClientResponse] =
      for {
        id       <- deploy
        response <- self.request(path, method, content, Headers(DynamicServer.APP_ID, id) ++ headers)
      } yield response

    def requestBodyAsString(
      path: Path = !!,
      method: Method = Method.GET,
      content: String = "",
      headers: Headers = Headers.empty,
    ): ZIO[EventLoopGroup with ChannelFactory with DynamicServer, Throwable, String] =
      request(path, method, content, headers).flatMap(_.getBodyAsString)

    def requestHeaderValueByName(
      path: Path = !!,
      method: Method = Method.GET,
      content: String = "",
      headers: Headers = Headers.empty,
    )(
      name: CharSequence,
    ): ZIO[EventLoopGroup with ChannelFactory with DynamicServer, Throwable, Option[String]] =
      request(path, method, content, headers).map(_.getHeaderValue(name))

    def requestStatus(
      path: Path = !!,
      method: Method = Method.GET,
      content: String = "",
      headers: Headers = Headers.empty,
    ): ZIO[EventLoopGroup with ChannelFactory with DynamicServer, Throwable, Status] =
      request(path, method, content, headers).map(_.status)

    def webSocketStatusCode(
      path: Path = !!,
      headers: Headers = Headers.empty,
    ): ZIO[SttpClient with DynamicServer, Throwable, Int] = for {
      id  <- deploy
      res <- self.webSocketRequest(path, Headers(DynamicServer.APP_ID, id) ++ headers)
    } yield res.code.code

    def requestBody(
      path: Path = !!,
      method: Method = Method.GET,
      content: String = "",
      headers: Headers = Headers.empty,
    ): ZIO[EventLoopGroup with ChannelFactory with DynamicServer, Throwable, Chunk[Byte]] =
      request(path, method, content, headers).flatMap(_.getBody)
  }
}
