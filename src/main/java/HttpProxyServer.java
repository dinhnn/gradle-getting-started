

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;

public class HttpProxyServer extends AbstractVerticle {
  static class OfflineMessage{
    Buffer body;
    long expired;
    public OfflineMessage(Buffer body,long expired){
      this.body = body;
      this.expired = expired;
    }
  }
	private String verifyToken;
	AtomicReference<HttpServerRequest> waitingRequest = new AtomicReference<HttpServerRequest>(null);
	EventBus eb;
	HttpClient client;
	private long messageTimeout;
	@Override
	public void start(Future<Void> startFuture) throws Exception {
		eb = vertx.eventBus();
		verifyToken = config().getString("verifyToken","7608132681920151");
		messageTimeout = config().getLong("timeout",300000L);
		client = vertx.createHttpClient();
		vertx.createHttpServer()
			.requestHandler(this::handleRequest)
			.websocketHandler(this::handleWebSocket)
			.listen(config().getInteger("port",Integer.getInteger("http.port")),config().getString("host", System.getProperty("http.address","0.0.0.0")),l->{
				if(l.succeeded()){
					startFuture.complete();
				} else {
					startFuture.fail(l.cause());
				}
			});
	}
	private static void appendString(Buffer buff,String s){
		if(s==null)buff.appendShort((short)-1);
		else {
			Buffer strBuff = Buffer.buffer(s);
			buff.appendShort((short)strBuff.length()).appendBuffer(strBuff);
		}
	}
	ServerWebSocket connectedChannel;
	private void handleWebSocket(ServerWebSocket ws){
	  if(!verifyToken.equals(ws.path().substring(1))){
	    ws.reject(400);
	    return;
	  }
	  connectedChannel = ws;
	  ws.binaryMessageHandler(this::handleWebsocketHandler);
	  ws.closeHandler(v->{
	    if(ws==connectedChannel)connectedChannel = null;
	  });
	  long now = System.currentTimeMillis();
	  while(!offline.isEmpty()){
      OfflineMessage msg = offline.remove();
      if(msg.expired>now){
        ws.writeBinaryMessage(msg.body);
      }
    }
	  
	}
	private void handleWebsocketHandler(Buffer buff){
	  int ofs = buff.getShort(0);
	  String respAddr = buff.getString(2,ofs);
	  eb.send(respAddr, buff.getBuffer(ofs,buff.length()));
	}
	private void handleRequest(HttpServerRequest req){
	  String token = req.getHeader("http-proxy-token");
	  if(token!=null && token.equals(verifyToken)){
	    String host = req.getHeader("http-proxy-host");
	    if(host!=null){
	      outgoing(req,host);
	    } else {
  	  	if(HttpMethod.GET.equals(req.method())){
  		    if(offline.isEmpty()){
  		    	req.connection().closeHandler(v->{
  		    		waitingRequest.compareAndSet(req,null);
  		    	});
  		      waitingRequest.set(req);
  		    } else {
    		    Buffer buffer = Buffer.buffer();
    		    int limit = 10;
    		    long now = System.currentTimeMillis();
    		    while(limit>0 && !offline.isEmpty()){
    		      limit--;
    		      OfflineMessage msg = offline.remove();
    		      if(msg.expired>now){
    		        buffer.appendInt(msg.body.length()).appendBuffer(msg.body);
    		      }
    		    }
    		    buffer.appendInt(0);
    		    req.response().end(buffer);
  		    }
  		  } else {		    
  		  	req.bodyHandler(buff->{
  		  		String respAddr = req.path().substring(1);
  		  		eb.send(respAddr, buff);
  		  		req.response().end();
  		  	});
  		  }
	    }
	  } else if(req.path().startsWith("/channel/")){
	  	String uuid = UUID.randomUUID().toString();
	  	//receive from outside
	  	Buffer buff = Buffer.buffer();
	  	appendString(buff,uuid);
	  	buff.appendInt(req.method().ordinal());
	  	String path = req.query()!=null?req.path()+"?"+req.query():req.path();
	  	appendString(buff,path);
	  	MultiMap headers = req.headers();
	  	if(headers!=null){
	  		buff.appendShort((short)headers.size());
		  	for(String name:headers.names()){
		  		appendString(buff,name);
		  		appendString(buff,headers.get(name));
		  	}
	  	} else {
	  		buff.appendShort((short)0);
	  	}
	  	req.handler(buff::appendBuffer);
	  	req.endHandler(v->{
	  		MessageConsumer<Buffer> mc = eb.consumer(uuid);
	  		long timer = vertx.setTimer(30000,l->{
	  			mc.unregister();
	  			req.response().setStatusCode(504).end("Proxy Timeout");
	  		});
				mc.handler(msg->{
					vertx.cancelTimer(timer);
	  			HttpServerResponse resp = req.response();
	  			Buffer respBuff = msg.body();
	  			int ofs = 0;
	  			resp.setStatusCode(respBuff.getShort(ofs));
	  			ofs+=2;
	  			int nHeader = respBuff.getShort(ofs);
	  			ofs+=2;
	  			for(int i = 0;i<nHeader;i++){
	  				int len = respBuff.getShort(ofs);
	  				ofs+=2;
	  				String name = respBuff.getBuffer(ofs,ofs+len).toString();
	  				ofs+=len;
	  				len = respBuff.getShort(ofs);
	  				ofs+=2;
	  				String value = respBuff.getBuffer(ofs,ofs+len).toString();
	  				ofs+=len;
	  				resp.putHeader(name, value);
	  			}
	  			resp.end(respBuff.getBuffer(ofs,respBuff.length()));
	  			mc.unregister();
	  		});
	  		mc.completionHandler(ar->{
	  			if(ar.succeeded()){
	  			  if(connectedChannel!=null){
	  			    connectedChannel.writeBinaryMessage(buff);
	  			  } else {
  	  				HttpServerRequest pollingRequest = waitingRequest.getAndSet(null);
  	  				if(pollingRequest!=null){
  	  					System.out.println("forward to client");
  	  					pollingRequest.response().end(Buffer.buffer(buff.length()+8).appendInt(buff.length()).appendBuffer(buff).appendInt(0));
  	  				} else {
  	  					System.out.println("add to queue");
  	  					offline.add(new OfflineMessage(buff,System.currentTimeMillis()+messageTimeout));
  	  				}
	  			  }
	  			} else {
	  				vertx.cancelTimer(timer);
	  				req.response().setStatusCode(500).end(ar.cause().getMessage());
	  			}
	  		});
	  		
	  	});
	  } else {
	  	req.response().end();
	  }
	}
	Queue<OfflineMessage> offline = new LinkedBlockingQueue<>();
	private void outgoing(HttpServerRequest req,String host){
	  req.pause();
	  StringBuilder sb =new StringBuilder(host).append(req.path());
	  if(req.query()!=null)sb.append('?').append(req.query());
	  HttpClientRequest clientReq = client.requestAbs(req.method(),sb.toString(),clientResp->{
	    clientResp.pause();
	    HttpServerResponse resp = req.response();	    
	    resp.headers().addAll(clientResp.headers());
	    clientResp.bodyHandler(resp::end);
	    clientResp.resume();
	  });
	  req.headers().forEach(entry->{
	    String headerName = entry.getKey();
	    if("Host".equals(headerName) || headerName.startsWith("http-proxy-") || headerName.startsWith("X-"))return;
	    clientReq.putHeader(headerName,entry.getValue());
	  });
	  req.bodyHandler(clientReq::end);
	  req.resume();
	}
}
