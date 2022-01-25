/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 * 基于线程局部栈的轻量级线程池
 *
 * @param <T> the type of the pooled object 需要池化的的对象类型
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    // id 生成器
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    // 获取旧值设置个新值
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    // 每个线程的默认最大容量值
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    // 每个线程的最大容量
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    // 初始化容量
    private static final int INITIAL_CAPACITY;
    // 最大共享容量
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    // 每个线程最大的廷迟队列
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    // 链接容量
    private static final int LINK_CAPACITY;
    // 比率
    private static final int RATIO;
    // 廷迟排队率
    private static final int DELAYED_QUEUE_RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // 可能对不同的对象有不同的maxCapacity
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));

        // 如果每个线程最大容量 < 0则使用默认值
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));  // CPU核数的2倍

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));
        // 廷迟队列回收率
        DELAYED_QUEUE_RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.delayedQueue.ratio", RATIO));

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: {}", DELAYED_QUEUE_RATIO);
            }
        }
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int interval;
    private final int maxDelayedQueuesPerThread;
    private final int delayedQueueInterval;

    // 保存线程的共享对象，而这里的共享对象就是一个Stack类型的对象
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread, delayedQueueInterval);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        // MAX_SHARED_CAPACITY_FACTOR的默认值为2
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        // RATIO默认值为8, MAX_DELAYED_QUEUES_PER_THREAD为CPU核数的2倍
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, ratio, maxDelayedQueuesPerThread,
                DELAYED_QUEUE_RATIO);
    }

    // Recycler(4096,2, 8, 8, 8)
    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        interval = max(0, ratio); // = 8
        delayedQueueInterval = max(0, delayedQueueRatio);  // = 8
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        Stack<T> stack = threadLocal.get();
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    @SuppressWarnings("unchecked")
    private static final class DefaultHandle<T> implements Handle<T> {
        // 上次回收的ID生成器
        private static final AtomicIntegerFieldUpdater<DefaultHandle<?>> LAST_RECYCLED_ID_UPDATER;

        // 定义一个原子更新DefaultHandle.class 字段的变量
        static {
            AtomicIntegerFieldUpdater<?> updater = AtomicIntegerFieldUpdater.newUpdater(
                    DefaultHandle.class, "lastRecycledId");
            LAST_RECYCLED_ID_UPDATER = (AtomicIntegerFieldUpdater<DefaultHandle<?>>) updater;
        }

        // 最后一个回收ID
        volatile int lastRecycledId;

        // 回收ID
        int recycleId;

        // 标识已经被回收
        boolean hasBeenRecycled;

        // 栈
        Stack<?> stack;

        // 对象
        Object value;

        // 初始化 DefaultHandle类
        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            // 如果当前所持有的对象对传入的对象不一样，则抛出异常
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            // 这个就是传说的写时复制技术？
            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }
            // 将当前的defaultHandle对象推入栈中
            stack.push(this);
        }

        // 比较并设置上次回收的ID
        public boolean compareAndSetLastRecycledId(int expectLastRecycledId, int updateLastRecycledId) {
            // Use "weak…" because we do not need synchronize-with ordering, only atomicity.
            // Also, spurious failures are fine, since no code should rely on recycling for correctness.
            return LAST_RECYCLED_ID_UPDATER.weakCompareAndSet(this, expectLastRecycledId, updateLastRecycledId);
        }
    }

    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        // Link扩展了AtomicInteger类成为内在函数，这个Link将用于他自已写入索引
        // 一个Link对象包括了DefaultHandle对象数组 elements、一个读索引、下一下Link指针
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            int readIndex;
            Link next;
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        // 它重要的是不包括任何Stack和WeakOrderQueue的引用 ---- Head类
        private static final class Head {
            // 可用的共享容量
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             * 回收所有已用空间还取消链接节点，以防止Gc裙带关系
             */
            void reclaimAllSpaceAndUnlink() {
                // 将link赋值head
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                // 这里计算reclaimSpace的值，并且取消当前Link的链接
                while (head != null) {
                    // 回收空间 = 已有空间加Link容量
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                // 添加回收空间数量
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            // 重连
            void relink(Link link) {
                reclaimSpace(LINK_CAPACITY);
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             * 如果有足够的引用空间创建一个新的Link返回，否则返回null
             */
            Link newLink() {
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            // 预留链接空间，自旋判断更改availableSharedCapacity，步骤
            // 自旋 =====>
            // 获取 availableSharedCapacity变量
            // 如果小于LINK_CAPACITY则返回false表示没有空间
            // 如果大于LINK_CAPACITY则将现有的值减去LINK_CAPACITY，返回true
            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        // 指向相同的栈另一个廷迟队列的指针
        private WeakOrderQueue next;
        // 自动增长
        private final int id = ID_GENERATOR.getAndIncrement();
        // DefaultHandle 回收的间隔数量阀值
        private final int interval;
        // 处理回收计数
        private int handleRecycleCount;

        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        // 初始化一个Head 对象 将head.link
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super(thread);
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            // 分配链接保留空间
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);

            return queue;
        }

        // 返回队列下一个值
        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        // 回收所有空间和链接
        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink();
            next = null;
        }

        // 将handle添加到Link对象的elements中
        void add(DefaultHandle<?> handle) {
            // 比较更新最后的回收id
            if (!handle.compareAndSetLastRecycledId(0, id)) {
                // Separate threads could be racing to add the handle to each their own WeakOrderQueue.
                // We only add the handle to the queue if we win the race and observe that lastRecycledId is zero.
                return;
            }

            // While we also enforce the recycling ratio when we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control
            // 我们将在对象从WeakOrderQueue传输到Stack时也会强制执回收率，但是我们最好尽早强制回收，不这样做可能会让WeakOrderQueue增长得非常快而不受控制
            // hasBeenRecycled 标识已经被回收
            if (!handle.hasBeenRecycled) {
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the item to prevent from recycling too aggressively.
                    return;
                }

                handleRecycleCount = 0;
            }

            Link tail = this.tail;
            int writeIndex;
            // 如果写索引值与LINK_CAPACITY相等，表示需要创建Link了
            // 这里获取了新的Link对象，由于Link对象实现了AtomicInteger可以获取到自增ID
            // tail.get()表示获取当前是Link中已经填充元素的个数，如果等于16说明元素已经填充满
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                Link link = head.newLink();
                if (link == null) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                // 将尾节点指向新创建的Link，并且原来尾节点的next的节点指向新创建的Link
                this.tail = tail = tail.next = link;
                // 获取新Link的写入索引位置，由于是新创建的Link所有索引位置返回的是0
                writeIndex = tail.get();
            }
            // 根椐上面获取的索引writeIndex 与 将Handle赋值到对应位置
            tail.elements[writeIndex] = handle;
            // 将handle的stack属性设置为null, 表示当前handle不是通过stack进行回收的
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // 维护一下一次写入的位置
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        // 将尽可能多的item从这个队列传输到stack中，如果有任何item被传输则返回true
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head.link;
            if (head == null) {
                return false;
            }

            // 如果读索引 等于 LINK_CAPACITY
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) { // head.next为null
                    return false;
                }
                head = head.next;
                this.head.relink(head); // 重连
            }

            // 这里有表示有几个DefaultHandle 数量
            final int srcStart = head.readIndex;
            int srcEnd = head.get();
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            final int dstSize = dst.size;
            // 预期容量
            final int expectedCapacity = dstSize + srcSize;

            if (expectedCapacity > dst.elements.length) {
                // Stack 增加容量
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                // actualCapacity 实际容量
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    // 如果回收ID为0
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;

                    // 回收将Handle设置为true就算是丢弃
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.relink(head.next);
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                // destination 目标栈已满
                return false;
            }
        }
    }

    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        final WeakReference<Thread> threadRef;
        final AtomicInteger availableSharedCapacity;
        private final int maxDelayedQueues;

        private final int maxCapacity;
        private final int interval;
        private final int delayedQueueInterval;
        DefaultHandle<?>[] elements;
        int size; // Stack栈数量大小 elements
        private int handleRecycleCount;
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues, int delayedQueueInterval) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;

            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            this.delayedQueueInterval = delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        // 增加容量
        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1; // 两倍扩容
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity); // 如果新容量小于预期容量 并且 新容量小于最大容量

            // 新容量与最大值取小的
            newCapacity = min(newCapacity, maxCapacity);
            // 如果新容量不等于 elements.length不相等
            if (newCapacity != elements.length) {
                // 复制指定的数组，长度为传入的新的容量值,返回新的数组
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            // 栈大小
            int size = this.size;
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                size = this.size;
                if (size <= 0) {
                    // double check, avoid races
                    return null;
                }
            }
            size --;
            // 返回elements数组中位置最大的数 (后进后出）
            DefaultHandle ret = elements[size];
            elements[size] = null; // 相应的位置设置为null
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            // 调整size
            this.size = size;

            // 最后的回收不等于回收ID
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            // 设置recycleId  lastRecycledId为0
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        private boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        // 清除队列中的部分数据转换到Stack中去
        private boolean scavengeSome() {
            // 前一个WeakOrderQueue队列
            WeakOrderQueue prev;
            // 指向当前的游标
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                prev = null;
                cursor = head;
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            // 默认为不成功
            boolean success = false;
            do {
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.getNext();
                if (cursor.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        cursor.reclaimAllSpaceAndUnlink();
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        //
        void push(DefaultHandle<?> item) {
            // 获取当前线程
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                // 这个当前线程是属于Stack，我们现在能尝试push
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                // 当前线程不是属于栈的线程，或者属于栈的线程已经被收集。我们需要表示推送稍后推送
                pushLater(item, currentThread);
            }
        }

        private void pushNow(DefaultHandle<?> item) {
            if (item.recycleId != 0 || !item.compareAndSetLastRecycledId(0, OWN_THREAD_ID)) {
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = OWN_THREAD_ID;

            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        private void pushLater(DefaultHandle<?> item, Thread thread) {
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            // 从DELAYED_RECYCLED中获取一个Map<<Stack<?>, WeakOrderQueue>类型的队列 delayedRecycled
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();

            // 这个this 该怎么理解呢。这里的意思是创建stack的对象
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                // 这里的意思是回收队列数量 大于等于最大的廷迟队列数量最大值，就放到 WeakOrderQueue.DUMMY
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    // 添加一个虚拟列队，以便我知道应该删除该对象
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // 检查我们是否已经达到廷迟队列的最大数量，如果不能分配则丢弃对象
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // drop object
                    return;
                }
                // 将对象推入队列
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         * 分配一个新的WeakOrderQueue 如果不能或者返回null
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        // 如果DefaultHandle没有被回收 并且 handleRecycleCount小于interval，则累计handleRecycleCount数量
        // 则设置handleRecycleCount 等于 0。设置Handle为已用状态
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                handleRecycleCount = 0;
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
