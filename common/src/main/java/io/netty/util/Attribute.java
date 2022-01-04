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

/**
 * An attribute which allows to store a value reference. It may be updated atomically and so is thread-safe.
 * 允许存储值引用属性，它能厚子更新值 是线程安全的
 *
 * @param <T>   the type of the value it holds.
 */
public interface Attribute<T> {

    /**
     * Returns the key of this attribute.
     * 返回这个属性的key
     */
    AttributeKey<T> key();

    /**
     * Returns the current value, which may be {@code null}
     * 返回当前值，也可能返回Nulls
     */
    T get();

    /**
     * Sets the value
     */
    void set(T value);

    /**
     *  Atomically sets to the given value and returns the old value which may be {@code null} if non was set before.
     *  原子更新值并返回旧值，如果之前设置了non有可能返回Null
     */
    T getAndSet(T value);

    /**
     *  Atomically sets to the given value if this {@link Attribute}'s value is {@code null}.
     *  如果此 Attribute 的值为Null，则原子设置为给定值
     *  If it was not possible to set the value as it contains a value it will just return the current value.
     *  如果设置值失败了，因为包含一个值它将返回当前值
     */
    T setIfAbsent(T value);

    /**
     * Removes this attribute from the {@link AttributeMap} and returns the old value. Subsequent {@link #get()}
     * calls will return {@code null}.
     *
     * If you only want to return the old value and clear the {@link Attribute} while still keep it in the
     * {@link AttributeMap} use {@link #getAndSet(Object)} with a value of {@code null}.
     *
     * <p>
     * Be aware that even if you call this method another thread that has obtained a reference to this {@link Attribute}
     * via {@link AttributeMap#attr(AttributeKey)} will still operate on the same instance. That said if now another
     * thread or even the same thread later will call {@link AttributeMap#attr(AttributeKey)} again, a new
     * {@link Attribute} instance is created and so is not the same as the previous one that was removed. Because of
     * this special caution should be taken when you call {@link #remove()} or {@link #getAndRemove()}.
     *
     * @deprecated please consider using {@link #getAndSet(Object)} (with value of {@code null}).
     */
    @Deprecated
    T getAndRemove();

    /**
     * Atomically sets the value to the given updated value if the current value == the expected value.
     * If it the set was successful it returns {@code true} otherwise {@code false}.
     * 原子更新值 ，如果当前值与预期值相等则更新
     * 如果设置成功它返回true，否则返回false
     */
    boolean compareAndSet(T oldValue, T newValue);

    /**
     * Removes this attribute from the {@link AttributeMap}. Subsequent {@link #get()} calls will return @{code null}.
     *
     * If you only want to remove the value and clear the {@link Attribute} while still keep it in
     * {@link AttributeMap} use {@link #set(Object)} with a value of {@code null}.
     *
     * <p>
     * Be aware that even if you call this method another thread that has obtained a reference to this {@link Attribute}
     * via {@link AttributeMap#attr(AttributeKey)} will still operate on the same instance. That said if now another
     * thread or even the same thread later will call {@link AttributeMap#attr(AttributeKey)} again, a new
     * {@link Attribute} instance is created and so is not the same as the previous one that was removed. Because of
     * this special caution should be taken when you call {@link #remove()} or {@link #getAndRemove()}.
     *
     * @deprecated please consider using {@link #set(Object)} (with value of {@code null}).
     */
    @Deprecated
    void remove();
}
