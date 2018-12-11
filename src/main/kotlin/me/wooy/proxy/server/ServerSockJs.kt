package me.wooy.proxy.server

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.http.HttpServer
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.logging.LoggerFactory
import io.vertx.core.net.NetClient
import io.vertx.core.net.NetSocket
import io.vertx.ext.web.handler.sockjs.SockJSHandler
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions
import io.vertx.kotlin.core.net.connectAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.wooy.proxy.data.*
import java.util.concurrent.ConcurrentHashMap

class ServerSockJs:AbstractVerticle() {
  private val logger = LoggerFactory.getLogger(ServerSockJs::class.java)
  private lateinit var netClient: NetClient
  private lateinit var httpServer:HttpServer
  private val localMap: ConcurrentHashMap<String,NetSocket> = ConcurrentHashMap()
  override fun start(startFuture: Future<Void>) {
    netClient = vertx.createNetClient()
    httpServer = vertx.createHttpServer()
    httpServer.websocketHandler(this::socketHandler)

    vertx.executeBlocking<HttpServer>({
      httpServer.listen(1888,it.completer())
    }){
      logger.info("Proxy server listen at 1888")
      startFuture.complete()
    }
  }

  private fun socketHandler(sock: ServerWebSocket){
    sock.binaryMessageHandler { buffer ->
      GlobalScope.launch(vertx.dispatcher()) {
        when (buffer.getIntLE(0)) {
          Flag.CONNECT.ordinal -> clientConnectHandler(sock, ClientConnect(buffer))
          Flag.RAW.ordinal -> clientRawHandler(sock, RawData(buffer))
          Flag.HTTP.ordinal -> clientRequestHandler(sock, HttpData(buffer))
          else->logger.error(buffer.getIntLE(0))
        }
      }
    }
    sock.accept()
  }

  private suspend fun clientConnectHandler(sock: ServerWebSocket,data:ClientConnect){
    try {
      val net = netClient.connectAwait(data.port, data.host)
      net.handler { buffer->
        sock.writeBinaryMessage(RawData.create(data.uuid,buffer).toBuffer())
      }.closeHandler {
        localMap.remove(data.uuid)
      }
      localMap[data.uuid] = net
    }catch (e:Throwable){
      logger.warn(e.message)
      sock.writeBinaryMessage(Exception.create(data.uuid,e.localizedMessage).toBuffer())
      return
    }
    sock.writeBinaryMessage(ConnectSuccess.create(data.uuid).toBuffer())
  }

  private fun clientRawHandler(sock: ServerWebSocket,data: RawData){
    val net = localMap[data.uuid]
    net?.write(data.data)?:let{
      sock.writeBinaryMessage(Exception.create(data.uuid,"Remote socket has closed").toBuffer())
    }
  }

  private suspend fun clientRequestHandler(sock: ServerWebSocket,data:HttpData){
    try{
      val net = netClient.connectAwait(data.port,data.host)
      net.handler {buffer->
        sock.writeBinaryMessage(RawData.create(data.uuid,buffer).toBuffer())
      }
      net.write(data.data)
    }catch (e:Throwable){
      logger.warn(e.message)
      sock.writeBinaryMessage(Exception.create(data.uuid,e.localizedMessage).toBuffer())
      return
    }
  }
}
