package io.netty.buffer;

import org.junit.Test;

public class CompositeByteBufTest {

    public CompositeByteBuf byteBuf = ByteBufAllocator.DEFAULT.compositeBuffer();

    @Test
    public void addComponent(){

        byteBuf.addComponent(Unpooled.wrappedBuffer(("12345").getBytes()));
        byteBuf.addComponent(Unpooled.wrappedBuffer(("abcdef").getBytes()));

        System.out.println(ByteBufUtil.getBytes(byteBuf).toString());

    }
}
