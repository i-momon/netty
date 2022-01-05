/*
 * Copyright 2012 The Netty Project
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

import io.netty.util.internal.ObjectUtil;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Default {@link AttributeMap} implementation which not exibit any blocking behaviour on attribute lookup while using a
 * copy-on-write approach on the modify path.<br> Attributes lookup and remove exibit {@code O(logn)} time worst-case
 * complexity, hence {@code attribute::set(null)} is to be preferred to {@code remove}.
 */

// DefaultAttributeMap 与 JDK Map有什么不同，为什么还要重新设计一个Map呢
// 在大量连接数下，ConcurrentHashMap显得非常吃内存
// 优点：代码少、简单、内存占用相对较小

public class DefaultAttributeMap implements AttributeMap {

    // 利用原子更新特性 DefaultAttributeMap类中 字段类型DefaultAttribute[]， 字段名称为attributes
    private static final AtomicReferenceFieldUpdater<DefaultAttributeMap, DefaultAttribute[]> ATTRIBUTES_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultAttributeMap.class, DefaultAttribute[].class, "attributes");


    private static final DefaultAttribute[] EMPTY_ATTRIBUTES = new DefaultAttribute[0];

    /**
     * Similarly to {@code Arrays::binarySearch} it perform a binary search optimized for this use case, in order to
     * save polymorphic calls (on comparator side) and unnecessary class checks.
     * 与Arrays::binarySearch相似使用二分查找优化查找，为了节省查找和不需要类检查
     */
    private static int searchAttributeByKey(DefaultAttribute[] sortedAttributes, AttributeKey<?> key) {
        int low = 0;
        int high = sortedAttributes.length - 1;

        while (low <= high) {
            // >>> 是无符号右移
            int mid = low + high >>> 1;
            DefaultAttribute midVal = sortedAttributes[mid];
            AttributeKey midValKey = midVal.key;
            if (midValKey == key) {
                return mid;
            }
            int midValKeyId = midValKey.id();
            int keyId = key.id();
            // 如果两个AttributeKey对象不一样抛出异常
            assert midValKeyId != keyId;
            boolean searchRight = midValKeyId < keyId;
            if (searchRight) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return -(low + 1);
    }

    /**
     * 在sortedSrc数组中找到插入位置index，将sortedSrc从index开始的往后的数据迁到copy数组中，再将小于index的数据迁移进去
     * @param sortedSrc
     * @param srcLength
     * @param copy
     * @param toInsert
     */
    private static void orderedCopyOnInsert(DefaultAttribute[] sortedSrc, int srcLength, DefaultAttribute[] copy,
                                            DefaultAttribute toInsert) {
        // let's walk backward, because as a rule of thumb, toInsert.key.id() tends to be higher for new keys
        // 这里有个小技巧，从后往前遍历一般新键会在后面，减少查找次数
        final int id = toInsert.key.id();
        int i;
        for (i = srcLength - 1; i >= 0; i--) {
            DefaultAttribute attribute = sortedSrc[i];
            assert attribute.key.id() != id;
            if (attribute.key.id() < id) {
                break;
            }
            copy[i + 1] = sortedSrc[i];
        }
        copy[i + 1] = toInsert;
        final int toCopy = i + 1;
        if (toCopy > 0) {
            System.arraycopy(sortedSrc, 0, copy, 0, toCopy);
        }
    }

    private volatile DefaultAttribute[] attributes = EMPTY_ATTRIBUTES;

    // 根椐AttributeKey 获取Attribute如果没有找到Attribute则创建一个Attribute
    @SuppressWarnings("unchecked")
    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        ObjectUtil.checkNotNull(key, "key");
        DefaultAttribute newAttribute = null;
        for (;;) {
            final DefaultAttribute[] attributes = this.attributes;
            // 根椐key查找在attributes数组中的位置
            final int index = searchAttributeByKey(attributes, key);

            final DefaultAttribute[] newAttributes;
            if (index >= 0) {
                final DefaultAttribute attribute = attributes[index];
                assert attribute.key() == key;
                if (!attribute.isRemoved()) {
                    return attribute;
                }
                // let's try replace the removed attribute with a new one
                // 让我们用新的属性替换已删除的属性
                if (newAttribute == null) {
                    newAttribute = new DefaultAttribute<T>(this, key);
                }
                final int count = attributes.length;
                newAttributes = Arrays.copyOf(attributes, count);
                newAttributes[index] = newAttribute;
            } else {
                if (newAttribute == null) {
                    newAttribute = new DefaultAttribute<T>(this, key);
                }
                final int count = attributes.length;
                newAttributes = new DefaultAttribute[count + 1];
                orderedCopyOnInsert(attributes, count, newAttributes, newAttribute);
            }
            if (ATTRIBUTES_UPDATER.compareAndSet(this, attributes, newAttributes)) {
                return newAttribute;
            }
        }
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        ObjectUtil.checkNotNull(key, "key");
        return searchAttributeByKey(attributes, key) >= 0;
    }

    /**
     * 如果匹配了，则删除属性
     * 先查找attribute，找不到则返回
     * 根椐索引找到attribute ， 判断key()是否要相等
     * 删除掉元素后做copy操作
     * @param key
     * @param value
     * @param <T>
     */
    private <T> void removeAttributeIfMatch(AttributeKey<T> key, DefaultAttribute<T> value) {
        for (;;) {
            final DefaultAttribute[] attributes = this.attributes;
            final int index = searchAttributeByKey(attributes, key);
            if (index < 0) {
                return;
            }
            final DefaultAttribute attribute = attributes[index];
            assert attribute.key() == key;
            if (attribute != value) {
                return;
            }
            final int count = attributes.length;
            final int newCount = count - 1;
            final DefaultAttribute[] newAttributes =
                    newCount == 0? EMPTY_ATTRIBUTES : new DefaultAttribute[newCount];
            // perform 2 bulk copies  执行2个批量复制
            System.arraycopy(attributes, 0, newAttributes, 0, index);
            final int remaining = count - index - 1;
            if (remaining > 0) {
                System.arraycopy(attributes, index + 1, newAttributes, index, remaining);
            }
            if (ATTRIBUTES_UPDATER.compareAndSet(this, attributes, newAttributes)) {
                return;
            }
        }
    }

    @SuppressWarnings("serial")
    private static final class DefaultAttribute<T> extends AtomicReference<T> implements Attribute<T> {

        // AtomicReferenceFieldUpdater 这是一个基于反射的工具类，它能对指定类的【指定的volatile引用字段】进行【原子更新】。(注意这个字段不能是private的) 简单理解：就是对某个类中，被volatile修饰的字段进行原子更新。
        private static final AtomicReferenceFieldUpdater<DefaultAttribute, DefaultAttributeMap> MAP_UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(DefaultAttribute.class,
                                                       DefaultAttributeMap.class, "attributeMap");
        private static final long serialVersionUID = -2661411462200283011L;

        private volatile DefaultAttributeMap attributeMap;
        private final AttributeKey<T> key;

        DefaultAttribute(DefaultAttributeMap attributeMap, AttributeKey<T> key) {
            this.attributeMap = attributeMap;
            this.key = key;
        }

        @Override
        public AttributeKey<T> key() {
            return key;
        }

        private boolean isRemoved() {
            return attributeMap == null;
        }

        @Override
        public T setIfAbsent(T value) {
            // 一直自旋比较当前this是不是null，如果是的就更新为value，如果没有更新成功进行while 循环里面，这一步相当于初始化了
            while (!compareAndSet(null, value)) {
                // 获取当前存储的值，如果不为空就返回
                T old = get();
                if (old != null) {
                    return old;
                }
            }
            return null;
        }

        @Override
        public T getAndRemove() {
            final DefaultAttributeMap attributeMap = this.attributeMap;
            final boolean removed = attributeMap != null && MAP_UPDATER.compareAndSet(this, attributeMap, null);
            // 设置新值返回旧值
            T oldValue = getAndSet(null);
            if (removed) {
                attributeMap.removeAttributeIfMatch(key, this);
            }
            return oldValue;
        }

        @Override
        public void remove() {
            final DefaultAttributeMap attributeMap = this.attributeMap;
            final boolean removed = attributeMap != null && MAP_UPDATER.compareAndSet(this, attributeMap, null);
            set(null);
            if (removed) {
                attributeMap.removeAttributeIfMatch(key, this);
            }
        }
    }
}
