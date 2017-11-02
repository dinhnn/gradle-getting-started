package com.dinhnn.httpbridge;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

@RunWith(VertxUnitRunner.class)
public class HttpProxyServerTest {

 
  Vertx vertx;
  private String verifyToken;
  private String receiveAddress;
  @Before
  public void before(TestContext context) {
    vertx = Vertx.vertx();
    System.setProperty("http.port","8888");
    System.setProperty("http.address","127.0.0.1");
    verifyToken = "7608132681920151";
    Async async = context.async();
    vertx.runOnContext(v->{
      vertx.deployVerticle(HttpProxyServer.class.getName(),context.asyncAssertSuccess());
      async.complete();
    });
    
  }
  @After
  public void after(TestContext context){
    vertx.close();
  }

  //@Test
  public void receive(TestContext context){
    Async async = context.async();
    vertx.runOnContext(v->{
      eb = vertx.eventBus();
      HttpClientOptions options = new HttpClientOptions().setDefaultHost("127.0.0.1")
          .setDefaultPort(8888);
      source = vertx.createHttpClient(options);
      receiveAddress = UUID.randomUUID().toString();
      
      eb.<JsonObject>consumer(receiveAddress,msg->{
        JsonObject body = msg.body();
        System.out.println(body);
        String uuid = body.getString("uuid");
        Buffer resp = Buffer.buffer().appendShort((short) 200);
        resp.appendShort((short) 0);
        resp.appendString("OK");
        source.post('/' + uuid, r -> {
          }).putHeader("http-proxy-token", verifyToken).end(resp);
        
      }).completionHandler(context.asyncAssertSuccess());
      polling();
      vertx.createHttpClient().postAbs("http://127.0.0.1:8888/channel/hello",resp->{
        resp.bodyHandler(body->{
          context.assertEquals("OK",body.toString());
          async.complete();
        });
      }).end("Hello");
    });
  }
  
  @Test
  public void send(TestContext context){
    eb = vertx.eventBus();
    HttpClientOptions options = new HttpClientOptions().setDefaultHost("127.0.0.1")
        .setDefaultPort(8888);
    source = vertx.createHttpClient(options);
    Async async = context.async();
    source.get("/", resp->{
      System.out.println(resp.headers());
      resp.bodyHandler(System.out::println);
      async.complete();
    }).putHeader("http-proxy-token", verifyToken).putHeader("http-proxy-host","https://google.com").exceptionHandler(th -> polling()).end();
  }
  
  AtomicReference<HttpServerRequest> waitingRequest = new AtomicReference<HttpServerRequest>(null);
  // AtomicReference<String> eventBusAddress = new
  // AtomicReference<String>("longpoll");
  EventBus eb;
  HttpClient source;

  private void polling() {
    source.get("/", this::handlePolling).putHeader("http-proxy-token", verifyToken).exceptionHandler(th -> polling())
        .end();
  }

  private void handlePolling(HttpClientResponse resp) {
    if (resp.statusCode() == 200) {
      resp.exceptionHandler(th -> {
        polling();
      });
      resp.bodyHandler(buff -> {
        System.out.println("handle polling:" + buff.length());
        int ofs = 0;
        int len = buff.getInt(ofs);
        ofs += 4;
        while (len > 0) {
          forwardRequest(buff.getBuffer(ofs, ofs + len));
          ofs += len;
          len = buff.getInt(ofs);
          ofs += 4;
        }
        polling();
      });
    } else {
      polling();
    }
  }

  private void forwardRequest(Buffer buff) {
    int len;
    int ofs = 0;
    len = buff.getShort(ofs);
    ofs += 2;
    String uuid = buff.getBuffer(ofs, ofs + len).toString();
    ofs += len;
    HttpMethod method = HttpMethod.values()[buff.getInt(ofs)];
    ofs += 4;
    len = buff.getShort(ofs);
    ofs += 2;
    String path = buff.getBuffer(ofs, ofs + len).toString();
    ofs += len;
    System.out.println("forward request" + method + ":" + path);
    JsonObject json = new JsonObject()
        .put("uuid", uuid)
        .put("method", method)
        .put("path",path);
    //HttpClientRequest req = dest.request(method, path, resp -> forwardResponse(resp, uuid));
    int nHeader = buff.getShort(ofs);
    ofs += 2;
    JsonObject headers;
    json.put("headers",headers = new JsonObject());
    for (int i = 0; i < nHeader; i++) {
      len = buff.getShort(ofs);
      ofs += 2;
      String name = buff.getBuffer(ofs, ofs + len).toString();
      ofs += len;
      len = buff.getShort(ofs);
      ofs += 2;
      String value = buff.getBuffer(ofs, ofs + len).toString();
      ofs += len;
      headers.put(name, value);
    }
    json.put("body",buff.getBuffer(ofs, buff.length()).toString());
    eb.send(receiveAddress, json);
  }

  private static void appendString(Buffer buff, String s) {
    if (s == null)
      buff.appendShort((short) -1);
    else {
      Buffer strBuff = Buffer.buffer(s);
      buff.appendShort((short) strBuff.length()).appendBuffer(strBuff);
    }
  }
}
