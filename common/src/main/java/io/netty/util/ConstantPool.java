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

import io.netty.util.internal.PlatformDependent;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkNonEmpty;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * A pool of {@link Constant}s.
 *
 * @param <T> the type of the constant
 */

// 这是一个常量池，使用线程安全的Map存储，使用Long型生成一个Constant唯一标识。使用Map API putIfAbsent、get、containsKey等操作
// 注意一个方法 newConstant是需要子类复写的
public abstract class ConstantPool<T extends Constant<T>> {

    // ConcurrentMap是一个接口，是一个能够支持并发访问的Java.util.Map集合
    private final ConcurrentMap<String, T> constants = PlatformDependent.newConcurrentHashMap();

    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Shortcut of {@link #valueOf(String) valueOf(firstNameComponent.getName() + "#" + secondNameComponent)}.
     */
    // 根椐Class 和 String 名称获取或生成返回常量
    public T valueOf(Class<?> firstNameComponent, String secondNameComponent) {
        return valueOf(
                checkNotNull(firstNameComponent, "firstNameComponent").getName() +
                '#' +
                checkNotNull(secondNameComponent, "secondNameComponent"));
    }

    /**
     * Returns the {@link Constant} which is assigned to the specified {@code name}.
     * If there's no such {@link Constant}, a new one will be created and returned.
     * Once created, the subsequent calls with the same {@code name} will always return the previously created one
     * (i.e. singleton.)
     * 返回分派指定的Constant，如果没有这个Constant则会创建并返回一个新的
     * 创建后具有相同name后续调用始终返回先前创建的调用（单例）
     *
     * @param name the name of the {@link Constant}
     */
    public T valueOf(String name) {
        return getOrCreate(checkNonEmpty(name, "name"));
    }

    /**
     * Get existing constant by name or creates new one if not exists. Threadsafe
     *
     * @param name the name of the {@link Constant}
     */
    private T getOrCreate(String name) {
        T constant = constants.get(name);
        if (constant == null) {
            final T tempConstant = newConstant(nextId(), name);
            constant = constants.putIfAbsent(name, tempConstant);
            if (constant == null) {
                return tempConstant;
            }
        }

        return constant;
    }

    /**
     * Returns {@code true} if a {@link AttributeKey} exists for the given {@code name}.
     */
    public boolean exists(String name) {
        return constants.containsKey(checkNonEmpty(name, "name"));
    }

    /**
     * Creates a new {@link Constant} for the given {@code name} or fail with an
     * {@link IllegalArgumentException} if a {@link Constant} for the given {@code name} exists.
     */
    public T newInstance(String name) {
        return createOrThrow(checkNonEmpty(name, "name"));
    }

    /**
     * Creates constant by name or throws exception. Threadsafe
     *
     * @param name the name of the {@link Constant}
     */
    private T createOrThrow(String name) {
        T constant = constants.get(name);
        if (constant == null) {
            final T tempConstant = newConstant(nextId(), name);
            constant = constants.putIfAbsent(name, tempConstant);
            if (constant == null) {
                return tempConstant;
            }
        }

        throw new IllegalArgumentException(String.format("'%s' is already in use", name));
    }

    protected abstract T newConstant(int id, String name);

    @Deprecated
    public final int nextId() {
        return nextId.getAndIncrement();
    }
}
