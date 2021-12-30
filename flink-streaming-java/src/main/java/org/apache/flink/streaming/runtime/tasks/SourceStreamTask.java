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

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.Internal;
import org.apache.flink.runtime.checkpoint.CheckpointMetaData;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.streaming.api.checkpoint.ExternallyInducedSource;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxDefaultAction;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * {@link StreamTask} for executing a {@link StreamSource}.
 *
 * <p>One important aspect of this is that the checkpointing and the emission of elements must never
 * occur at the same time. The execution must be serial. This is achieved by having the contract
 * with the {@link SourceFunction} that it must only modify its state or emit elements in
 * a synchronized block that locks on the lock Object. Also, the modification of the state
 * and the emission of elements must happen in the same block of code that is protected by the
 * synchronized block.
 *
 * @param <OUT> Type of the output elements of this source.
 * @param <SRC> Type of the source function for the stream source operator
 * @param <OP> Type of the stream source operator
 */
@Internal
public class SourceStreamTask<OUT, SRC extends SourceFunction<OUT>, OP extends StreamSource<OUT, SRC>>
	extends StreamTask<OUT, OP> {

	private final LegacySourceFunctionThread sourceThread;

	private volatile boolean externallyInducedCheckpoints;

	/**
	 * Indicates whether this Task was purposefully finished (by finishTask()), in this case we
	 * want to ignore exceptions thrown after finishing, to ensure shutdown works smoothly.
	 */
	private volatile boolean isFinished = false;

	// TODO_WU 构造 Task 具体启动实例的时候，调用这个构造器
	// TODO_WU 1.11添加lock
	public SourceStreamTask(Environment env) {
		super(env);
		// TODO_WU 初始化线程：LegacySourceFunctionThread 用于产生 data
		this.sourceThread = new LegacySourceFunctionThread();
	}

	@Override
	protected void init() {
		// we check if the source is actually inducing the checkpoints, rather
		// than the trigger
		// TODO_WU 获取数据源数据产生逻辑SourceFunction
		SourceFunction<?> source = headOperator.getUserFunction();
		// TODO_WU 如果source实现了这个接口，说明接收到CheckpointCoordinator发来的触发checkpoint消息之时source不触发checkpoint
		// TODO_WU ExternallyInducedSource的默认实现类在SourceExternalCheckpointTriggerTest
		if (source instanceof ExternallyInducedSource) {
			externallyInducedCheckpoints = true;

			// TODO_WU 创建checkpoint触发钩子
			ExternallyInducedSource.CheckpointTrigger triggerHook = new ExternallyInducedSource.CheckpointTrigger() {

				@Override
				public void triggerCheckpoint(long checkpointId) throws FlinkException {
					// TODO - we need to see how to derive those. We should probably not encode this in the
					// TODO -   source's trigger message, but do a handshake in this task between the trigger
					// TODO -   message from the master, and the source's trigger notification
					final CheckpointOptions checkpointOptions = CheckpointOptions.forCheckpointWithDefaultLocation();
					final long timestamp = System.currentTimeMillis();

					final CheckpointMetaData checkpointMetaData = new CheckpointMetaData(checkpointId, timestamp);

					try {
						SourceStreamTask.super.triggerCheckpointAsync(checkpointMetaData, checkpointOptions, false)
							.get();
					}
					catch (RuntimeException e) {
						throw e;
					}
					catch (Exception e) {
						throw new FlinkException(e.getMessage(), e);
					}
				}
			};

			((ExternallyInducedSource<?, ?>) source).setCheckpointTrigger(triggerHook);
		}
		// TODO_WU 1.11 在此处配置checkpoint启动延迟时间监控
	}

	@Override
	protected void advanceToEndOfEventTime() throws Exception {
		headOperator.advanceToEndOfEventTime();
	}

	@Override
	protected void cleanup() {
		// does not hold any resources, so no cleanup needed
	}

	@Override
	protected void processInput(MailboxDefaultAction.Controller controller) throws Exception {

		// TODO_WU 阻塞
		controller.suspendDefaultAction();

		// Against the usual contract of this method, this implementation is not step-wise but blocking instead for
		// compatibility reasons with the current source interface (source functions run as a loop, not in steps).
		sourceThread.setTaskDescription(getName());
		sourceThread.start();
		sourceThread.getCompletionFuture().whenComplete((Void ignore, Throwable sourceThreadThrowable) -> {
			if (isCanceled() && ExceptionUtils.findThrowable(sourceThreadThrowable, InterruptedException.class).isPresent()) {
				mailboxProcessor.reportThrowable(new CancelTaskException(sourceThreadThrowable));
			} else if (!isFinished && sourceThreadThrowable != null) {
				mailboxProcessor.reportThrowable(sourceThreadThrowable);
			} else {
				mailboxProcessor.allActionsCompleted();
			}
		});
	}

	@Override
	protected void cancelTask() {
		try {
			if (headOperator != null) {
				headOperator.cancel();
			}
		}
		finally {
			if (sourceThread.isAlive()) {
				sourceThread.interrupt();
			} else if (!sourceThread.getCompletionFuture().isDone()) {
				// source thread didn't start
				sourceThread.getCompletionFuture().complete(null);
			}
		}
	}

	@Override
	protected void finishTask() throws Exception {
		isFinished = true;
		cancelTask();
	}

	@Override
	protected CompletableFuture<Void> getCompletionFuture() {
		return sourceThread.getCompletionFuture();
	}

	// ------------------------------------------------------------------------
	//  Checkpointing
	// ------------------------------------------------------------------------

	@Override
	public Future<Boolean> triggerCheckpointAsync(CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions, boolean advanceToEndOfEventTime) {
		// TODO_WU externallyInducedCheckpoints true 时source不触发cp
		if (!externallyInducedCheckpoints) {
			// TODO_WU 调用父类方法
			return super.triggerCheckpointAsync(checkpointMetaData, checkpointOptions, advanceToEndOfEventTime);
		}
		else {
			// we do not trigger checkpoints here, we simply state whether we can trigger them
			synchronized (getCheckpointLock()) {
				return CompletableFuture.completedFuture(isRunning());
			}
		}
	}

	@Override
	protected void declineCheckpoint(long checkpointId) {
		if (!externallyInducedCheckpoints) {
			super.declineCheckpoint(checkpointId);
		}
	}

	@Override
	protected void handleCheckpointException(Exception exception) {
		// For externally induced checkpoints, the exception would be passed via triggerCheckpointAsync future.
		if (!externallyInducedCheckpoints) {
			super.handleCheckpointException(exception);
		}
	}

	/**
	 * Runnable that executes the the source function in the head operator.
	 */
	private class LegacySourceFunctionThread extends Thread {

		private final CompletableFuture<Void> completionFuture;

		LegacySourceFunctionThread() {
			this.completionFuture = new CompletableFuture<>();
		}

		@Override
		public void run() {
			try {
				// TODO_WU 调用 source Operator 的 run
				headOperator.run(getCheckpointLock(), getStreamStatusMaintainer(), operatorChain);
				completionFuture.complete(null);
			} catch (Throwable t) {
				// Note, t can be also an InterruptedException
				completionFuture.completeExceptionally(t);
			}
		}

		public void setTaskDescription(final String taskDescription) {
			setName("Legacy Source Thread - " + taskDescription);
		}

		/**
		 * @return future that is completed once this thread completes. If this task {@link #isFailing()} and this thread
		 * is not alive (e.g. not started) returns a normally completed future.
		 */
		CompletableFuture<Void> getCompletionFuture() {
			return isFailing() && !isAlive() ? CompletableFuture.completedFuture(null) : completionFuture;
		}
	}
}
