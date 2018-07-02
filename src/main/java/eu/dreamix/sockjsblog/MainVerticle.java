package eu.dreamix.sockjsblog;

import co.paralleluniverse.fibers.Suspendable;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.sync.Sync;
import io.vertx.ext.sync.SyncVerticle;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeEvent;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;

public class MainVerticle extends SyncVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    private static final String EB_WS_CLIENT_ADDRESS = "ws-to-client";
    private static final String EB_WS_SERVER_ADDRESS = "ws-to-server";

    @Override
    @Suspendable
    public void start() {
        final HttpServer server = vertx.createHttpServer();
        final Router router = Router.router(vertx);
        defineHttpEndpoints(router);

        SockJSHandlerOptions options = new SockJSHandlerOptions().setHeartbeatInterval(5000);
        SockJSHandler sockJSHandler = SockJSHandler.create(vertx, options);
        BridgeOptions bo = new BridgeOptions()
            .addInboundPermitted(new PermittedOptions().setAddress(EB_WS_SERVER_ADDRESS))
            .addOutboundPermitted(new PermittedOptions().setAddress(EB_WS_CLIENT_ADDRESS));
        sockJSHandler.bridge(bo, Sync.fiberHandler(this::handleEBrequests));

        router.route("/ws/*").handler(sockJSHandler);
        vertx.eventBus().consumer(EB_WS_SERVER_ADDRESS, Sync.fiberHandler(this::handleClientMessage));
        server.requestHandler(router::accept).listen(9090);
    }

    private void defineHttpEndpoints(Router router) {
        router.route().handler(CorsHandler.create("*"));
        router.route().handler(BodyHandler.create());
        router.get("/*").handler(StaticHandler.create("webroot").setCachingEnabled(false));
        router.put("/sendMsgToClient").handler(this::sendMsgToClient);
    }

    @Suspendable
    private void handleEBrequests(BridgeEvent event) {
        if (event.type() == BridgeEventType.PUBLISH || event.type() == BridgeEventType.SEND) {
            logger.info("A websocket event occurred: {0}, rawMessage: {1}", event.type(), event.getRawMessage());
        }
        event.complete(true);
    }

    @Suspendable
    private void sendMsgToClient(RoutingContext ctx) {
        final JsonObject message;
        if (ctx.getBody() != null && ctx.getBody().length() > 0) {
            message = ctx.getBodyAsJson();
        } else {
            message = new JsonObject().put("msg", "Success");
        }
        vertx.eventBus().publish(EB_WS_CLIENT_ADDRESS, message.encode());
        ctx.response().setStatusCode(HttpResponseStatus.OK.code())
            .putHeader(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON)
            .end(message.toString());
    }

    private void handleClientMessage(Message message) {
        logger.info("A message received: {0}", message.body());
    }
}
