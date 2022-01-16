package io.netty.example.echo;

import io.netty.util.Recycler;

public class EchoRecyclerTest {

    private static final Recycler<Dome> RECYCLER = new Recycler<Dome>() {
        @Override
        protected Dome newObject(Handle<Dome> handle) {
            return new Dome(handle);
        }
    };

    static class Dome {
        private final Recycler.Handle<Dome> handle;

        public Dome(Recycler.Handle<Dome> handle) {
            this.handle = handle;
        }

        public void recycler() {
            handle.recycle(this);
        }

        public void PrintThread() {
            System.out.printf("current thread name %s: \n", Thread.currentThread());
        }
    }


    public static void main(String[] args) {
        Dome user1 = RECYCLER.get();
        user1.recycler();

        Dome user2 = RECYCLER.get();
        user2.recycler();

        user1.PrintThread();

        user2.PrintThread();

        System.out.println(user1==user2);
    }
}
