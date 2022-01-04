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
 * A singleton which is safe to compare via the {@code ==} operator. Created and managed by {@link ConstantPool}.
 * 一个可以通过 == 运算符进行比较的单例，由ConstantPool创建和管理
 */
public interface Constant<T extends Constant<T>> extends Comparable<T> {

    /**
     * Returns the unique number assigned to this {@link Constant}.
     * 返回唯一编号赋值给Constant，这个自增的id用一个很大的用处，用来做二分查找
     */
    int id();

    /**
     * Returns the name of this {@link Constant}.
     * 返回此Constant名称
     */
    String name();
}
