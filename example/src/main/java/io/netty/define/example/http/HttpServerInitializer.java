package io.netty.define.example.http;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

    protected void initChannel(SocketChannel channel) {
        try {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast(new HttpServerCodec());
            pipeline.addLast("httpAggregator", new HttpObjectAggregator(512 * 1024));
            pipeline.addLast(new HttpRequestHandler()); // 请求处理器
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
