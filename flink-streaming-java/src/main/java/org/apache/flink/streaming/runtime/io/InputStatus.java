/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.annotation.Internal;
import org.apache.flink.streaming.runtime.io.PushingAsyncDataInput.DataOutput;

/**
 * An {@link InputStatus} indicates one input state which might be currently
 * available, not available or already finished. It is returned while calling
 * {@link PushingAsyncDataInput#emitNext(DataOutput)}.
 */
@Internal
public enum InputStatus {

	/**
	 * // TODO_WU 表示在输入数据中还有更多的数据可以消费，当任务正常运行时，会一直处于MORE_AVAILABLE状态
	 * Indicator that more data is available and the input can be called immediately again
	 * to emit more data.
	 */
	MORE_AVAILABLE,

	/**
	 * // TODO_WU 表示当前没有数据可以消费，但是未来会有数据待处理，此时线程模型中的处理线程会被挂起并等待数据接入
	 * Indicator that no data is currently available, but more data will be available in the
	 * future again.
	 */
	NOTHING_AVAILABLE,

	/**
	 * // TODO_WU 表示数据已经达到最后的状态，之后不再有数据输入，也预示着整个Task终止
	 * Indicator that the input has reached the end of data.
	 */
	END_OF_INPUT
}
