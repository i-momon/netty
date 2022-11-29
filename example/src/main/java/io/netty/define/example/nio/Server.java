package io.netty.define.example.nio;

import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;

public class Server {
    private Selector selector;
    private ServerSocketChannel serverSocketChannel;
    private static final int port = 9090;

    Server() {

    }
}
