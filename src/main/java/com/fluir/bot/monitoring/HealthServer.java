package com.fluir.bot.monitoring;

import com.fluir.bot.audio.AudioPlayerManager;
import com.fluir.bot.persistence.PersistentStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.JDA;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

public final class HealthServer implements AutoCloseable {
    private final HttpServer server;
    private final PersistentStore store;
    private final Supplier<JDA> jda;
    private final AudioPlayerManager audio;
    private final String metricsToken;
    private final long startedAt = System.currentTimeMillis();

    public HealthServer(int port, PersistentStore store, Supplier<JDA> jda, AudioPlayerManager audio, String metricsToken) throws IOException {
        this.store=store; this.jda=jda; this.audio=audio; this.metricsToken=metricsToken;
        server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),16);
        server.createContext("/health",this::health);
        server.createContext("/metrics",this::metrics);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private void health(HttpExchange x)throws IOException{
        if(!"GET".equals(x.getRequestMethod())){send(x,405,"{\"status\":\"method_not_allowed\"}");return;}
        JDA instance=jda.get(); boolean ready=instance!=null&&instance.getStatus()==JDA.Status.CONNECTED;
        boolean ok=ready&&store.isHealthy();
        send(x,ok?200:503,"{\"status\":\""+(ok?"ok":"starting")+"\",\"discord\":"+ready+",\"database\":"+store.isHealthy()+"}");
    }

    private void metrics(HttpExchange x)throws IOException{
        if(metricsToken==null||metricsToken.isBlank()||!constantTimeEquals(metricsToken,x.getRequestHeaders().getFirst("Authorization"))){send(x,404,"{\"status\":\"not_found\"}");return;}
        long used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        send(x,200,"{\"uptime_seconds\":"+((System.currentTimeMillis()-startedAt)/1000)+",\"active_sessions\":"+audio.getSessionCount()+",\"memory_used_bytes\":"+used+"}");
    }

    private static boolean constantTimeEquals(String token,String header){String expected="Bearer "+token;if(header==null||header.length()!=expected.length())return false;int diff=0;for(int i=0;i<header.length();i++)diff|=header.charAt(i)^expected.charAt(i);return diff==0;}
    private static void send(HttpExchange x,int status,String body)throws IOException{byte[] data=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(status,data.length);try(var out=x.getResponseBody()){out.write(data);}}
    @Override public void close(){server.stop(1);}
}
