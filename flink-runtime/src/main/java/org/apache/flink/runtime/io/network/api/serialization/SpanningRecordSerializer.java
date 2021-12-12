/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.io.network.api.serialization;

import org.apache.flink.core.io.IOReadableWritable;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.apache.flink.runtime.io.network.buffer.BufferBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Record serializer which serializes the complete record to an intermediate
 * data serialization buffer and copies this buffer to target buffers
 * one-by-one using {@link #copyToBufferBuilder(BufferBuilder)}.
 *
 * @param <T> The type of the records that are serialized.
 */
public class SpanningRecordSerializer<T extends IOReadableWritable> implements RecordSerializer<T> {

	/** Flag to enable/disable checks, if buffer not set/full or pending serialization. */
	private static final boolean CHECKED = false;

	/** Intermediate data serialization. */
	private final DataOutputSerializer serializationBuffer;

	/** Intermediate buffer for data serialization (wrapped from {@link #serializationBuffer}). */
	private ByteBuffer dataBuffer;

	public SpanningRecordSerializer() {
		serializationBuffer = new DataOutputSerializer(128);

		// ensure initial state with hasRemaining false (for correct continueWritingWithNextBufferBuilder logic)
		dataBuffer = serializationBuffer.wrapAsByteBuffer();
	}

	/**
	 * Serializes the complete record to an intermediate data serialization buffer.
	 *
	 * @param record the record to serialize
	 */
	@Override
	public void serializeRecord(T record) throws IOException {
		if (CHECKED) {
			if (dataBuffer.hasRemaining()) {
				throw new IllegalStateException("Pending serialization of previous record.");
			}
		}

		// TODO_WU 1)清理serializationBuffer中的数据.实际上就是将byte[]数组的position参数置为0
		serializationBuffer.clear();
		// TODO_WU 2)设定serialization buffer数量
		// the initial capacity of the serialization buffer should be no less than 4
		serializationBuffer.skipBytesToWrite(4);

		// TODO_WU 3)将数据元素写入serializationBuffer的bytes[]数组，所有数据元素都实现了IOReadableWritable接口
		// write data and length
		record.write(serializationBuffer);

		// TODO_WU 4)获取serializationBuffer的长度信息并记录到serializationBuffer对象中
		int len = serializationBuffer.length() - 4;
		serializationBuffer.setPosition(0);
		serializationBuffer.writeInt(len);
		serializationBuffer.skipBytesToWrite(len);

		// TODO_WU 5)对serializationBuffer进行wrap处理，数据真正写到了 dataBuffer 中
		dataBuffer = serializationBuffer.wrapAsByteBuffer();
	}

	/**
	 * Copies an intermediate data serialization buffer into the target BufferBuilder.
	 *
	 * @param targetBuffer the target BufferBuilder to copy to
	 * @return how much information was written to the target buffer and
	 *         whether this buffer is full
	 */
	@Override
	public SerializationResult copyToBufferBuilder(BufferBuilder targetBuffer) {
		targetBuffer.append(dataBuffer);
		targetBuffer.commit();

		return getSerializationResult(targetBuffer);
	}

	private SerializationResult getSerializationResult(BufferBuilder targetBuffer) {
		if (dataBuffer.hasRemaining()) {
			return SerializationResult.PARTIAL_RECORD_MEMORY_SEGMENT_FULL;
		}
		return !targetBuffer.isFull()
			? SerializationResult.FULL_RECORD
			: SerializationResult.FULL_RECORD_MEMORY_SEGMENT_FULL;
	}

	@Override
	public void reset() {
		dataBuffer.position(0);
	}

	@Override
	public void prune() {
		serializationBuffer.pruneBuffer();
		dataBuffer = serializationBuffer.wrapAsByteBuffer();
	}

	@Override
	public boolean hasSerializedData() {
		return dataBuffer.hasRemaining();
	}
}
