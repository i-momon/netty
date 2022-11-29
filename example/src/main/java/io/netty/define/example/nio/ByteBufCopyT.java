package io.netty.define.example.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public class ByteBufCopyT {

    public static void main(String[] args) {
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(8);

        // 写入
        buf.writeInt(128);

        // 拷贝一个新对象
        ByteBuf cp = buf.copy();

        System.out.println("readerIndex = " + buf.readerIndex() + "writerIndex = " + buf.writerIndex());

        // 修改前对象的值
        cp.setInt(0, 126);

        //修改值互不相信
        System.out.println(buf.readInt());
        System.out.println(cp.readInt());

        //全部需要释放
        buf.release();
        cp.release();
    }

}
