package io.netty.define.example.nio;

import io.netty.buffer.*;
import io.netty.util.CharsetUtil;

import java.nio.charset.StandardCharsets;

public class CompositeByteBufT {

    public static void main(String[] args) {
        CompositeByteBuf byteBuf = ByteBufAllocator.DEFAULT.compositeBuffer(5);

        byte[] s = ("12345").getBytes(CharsetUtil.UTF_8);
        byte[] b = ("abcdef").getBytes(CharsetUtil.UTF_8);

        byteBuf.addComponent(true, Unpooled.wrappedBuffer(s));
        System.out.println("writerIndex : " + byteBuf.writerIndex() + " readerIndex : " + byteBuf.readerIndex());

        ByteBuf bb = Unpooled.wrappedBuffer(b);
        byteBuf.addComponent(true, bb);
        System.out.println("bb : " + bb.readableBytes());

        System.out.println("writerIndex : " + byteBuf.writerIndex() + " readerIndex : " + byteBuf.readerIndex());

//        byte[] result = new byte[byteBuf.readableBytes()];
//        byteBuf.readBytes(result);
//        System.out.println(new String(result));

        byte[] c = ("asdkfdsfd").getBytes(CharsetUtil.UTF_8);
        byteBuf.addComponent(true, Unpooled.wrappedBuffer(c));

//        System.out.println(byteBuf.readerIndex());
        System.out.println(byteBuf.writerIndex());


//        String a = "ccc";
//        String b = "dddd";
//        ByteBuf buf1 = Unpooled.wrappedBuffer(a.getBytes(CharsetUtil.UTF_8));
//        ByteBuf buf2 = Unpooled.wrappedBuffer(b.getBytes(CharsetUtil.UTF_8));
//        ByteBuf compositeByteBuf = Unpooled.wrappedBuffer(buf1,buf2);
//
//        int size = compositeByteBuf.readableBytes();
//        byte[] bytes = new byte[size];
//        compositeByteBuf.readBytes(bytes);
//        String value = new String(bytes,CharsetUtil.UTF_8);
//        System.out.println("composite buff result : " + value);

    }

}
