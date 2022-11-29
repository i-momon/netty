package io.netty.define.example.http;

public class main {

    public static void main(String[] args) throws Exception{
        HttpServer server = new HttpServer(8081);// 8081为启动端口
        server.start();
    }

}
