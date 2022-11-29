package io.netty.define.example.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public class ByteBufDuplicateT {
    public static void main(String[] args) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(8);

        //写入值
        buf.writeInt(128);

        //拷贝一个新对象，在新对象上修改不会影响前对象
        ByteBuf cp = buf.duplicate();

        //返回0-4 readerIndex和writerIndex不受duplicate方法影响
        System.out.println("readerIndex=" + buf.readerIndex() + "| writerIndex=" + buf.writerIndex());

        //修改前对象的值
        cp.setInt(0, 126);

        //共享一块缓冲区，修改互相影响
        System.out.println(buf.readInt());
        System.out.println(cp.readInt());

        //不需要全部需要释放，只需要释放buf
        buf.release();

        //它不需要释放
        //cp.release();
    }
}
