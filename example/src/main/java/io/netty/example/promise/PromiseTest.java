package io.netty.example.promise;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class PromiseTest {

    public static void main(String[] args) {
        PromiseTest promiseTest = new PromiseTest();
        Promise<String> promise = promiseTest.doSomething("This");
        promise.addListener(future -> System.out.println(promise.get() + ", something is done"));
    }

    public Promise<String> doSomething(String value) {
        NioEventLoopGroup loop = new NioEventLoopGroup();

        DefaultPromise<String> promise = new DefaultPromise<>(loop.next());

        loop.schedule(()->{
            try {
                Thread.sleep(1000);
                promise.setSuccess("执行成功。" + value);
                return promise;
            } catch (Exception e) {
                promise.setFailure(e);
            }
            return promise;
        }, 0, TimeUnit.SECONDS);
        return promise;
    }
}
