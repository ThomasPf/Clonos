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

package org.apache.flink.runtime.io.network.netty;

import org.apache.flink.runtime.causal.log.CausalLogManager;
import org.apache.flink.runtime.io.network.NetworkClientHandler;
import org.apache.flink.runtime.io.network.TaskEventDispatcher;
import org.apache.flink.runtime.io.network.partition.ResultPartitionProvider;

import org.apache.flink.shaded.netty4.io.netty.channel.ChannelHandler;

/**
 * Defines the server and client channel handlers, i.e. the protocol, used by netty.
 */
public class NettyProtocol {

	private final NettyMessage.NettyMessageEncoder
		messageEncoder;

	private final ResultPartitionProvider partitionProvider;
	private final TaskEventDispatcher taskEventDispatcher;

	private final boolean creditBasedEnabled;
	private final boolean sensitiveFailureDetectionEnabled;
	private final CausalLogManager causalLogManager;

	NettyProtocol(ResultPartitionProvider partitionProvider, TaskEventDispatcher taskEventDispatcher, boolean creditBasedEnabled) {
		this(partitionProvider, taskEventDispatcher, creditBasedEnabled, null, true);
	}
	NettyProtocol(ResultPartitionProvider partitionProvider, TaskEventDispatcher taskEventDispatcher, boolean creditBasedEnabled, CausalLogManager causalLogManager, boolean sensitiveFailureDetectionEnabled) {
		this.partitionProvider = partitionProvider;
		this.taskEventDispatcher = taskEventDispatcher;
		this.creditBasedEnabled = creditBasedEnabled;
		this.causalLogManager = causalLogManager;
		this.sensitiveFailureDetectionEnabled = sensitiveFailureDetectionEnabled;
		this.messageEncoder = new NettyMessage.NettyMessageEncoder(causalLogManager);
	}

	/**
	 * Returns the server channel handlers.
	 *
	 * <pre>
	 * +-------------------------------------------------------------------+
	 * |                        SERVER CHANNEL PIPELINE                    |
	 * |                                                                   |
	 * |    +----------+----------+ (3) write  +----------------------+    |
	 * |    | Queue of queues     +----------->| Message encoder      |    |
	 * |    +----------+----------+            +-----------+----------+    |
	 * |              /|\                                 \|/              |
	 * |               | (2) enqueue                       |               |
	 * |    +----------+----------+                        |               |
	 * |    | Request handler     |                        |               |
	 * |    +----------+----------+                        |               |
	 * |              /|\                                  |               |
	 * |               |                                   |               |
	 * |   +-----------+-----------+                       |               |
	 * |   | Message+Frame decoder |                       |               |
	 * |   +-----------+-----------+                       |               |
	 * |              /|\                                  |               |
	 * +---------------+-----------------------------------+---------------+
	 * |               | (1) client request               \|/
	 * +---------------+-----------------------------------+---------------+
	 * |               |                                   |               |
	 * |       [ Socket.read() ]                    [ Socket.write() ]     |
	 * |                                                                   |
	 * |  Netty Internal I/O Threads (Transport Implementation)            |
	 * +-------------------------------------------------------------------+
	 * </pre>
	 *
	 * @return channel handlers
	 */
	public ChannelHandler[] getServerChannelHandlers() {
		PartitionRequestQueue queueOfPartitionQueues = new PartitionRequestQueue(causalLogManager);
		PartitionRequestServerHandler serverHandler = new PartitionRequestServerHandler(
			partitionProvider, taskEventDispatcher, queueOfPartitionQueues, creditBasedEnabled, sensitiveFailureDetectionEnabled);

		return new ChannelHandler[] {
			messageEncoder,
			new NettyMessage.NettyMessageDecoder(!creditBasedEnabled, causalLogManager),
			serverHandler,
			queueOfPartitionQueues
		};
	}

	/**
	 * Returns the client channel handlers.
	 *
	 * <pre>
	 *     +-----------+----------+            +----------------------+
	 *     | Remote input channel |            | request client       |
	 *     +-----------+----------+            +-----------+----------+
	 *                 |                                   | (1) write
	 * +---------------+-----------------------------------+---------------+
	 * |               |     CLIENT CHANNEL PIPELINE       |               |
	 * |               |                                  \|/              |
	 * |    +----------+----------+            +----------------------+    |
	 * |    | Request handler     +            | Message encoder      |    |
	 * |    +----------+----------+            +-----------+----------+    |
	 * |              /|\                                 \|/              |
	 * |               |                                   |               |
	 * |    +----------+------------+                      |               |
	 * |    | Message+Frame decoder |                      |               |
	 * |    +----------+------------+                      |               |
	 * |              /|\                                  |               |
	 * +---------------+-----------------------------------+---------------+
	 * |               | (3) server response              \|/ (2) client request
	 * +---------------+-----------------------------------+---------------+
	 * |               |                                   |               |
	 * |       [ Socket.read() ]                    [ Socket.write() ]     |
	 * |                                                                   |
	 * |  Netty Internal I/O Threads (Transport Implementation)            |
	 * +-------------------------------------------------------------------+
	 * </pre>
	 *
	 * @return channel handlers
	 */
	public ChannelHandler[] getClientChannelHandlers() {
		NetworkClientHandler networkClientHandler =
			creditBasedEnabled ? new CreditBasedPartitionRequestClientHandler(sensitiveFailureDetectionEnabled) :
				new PartitionRequestClientHandler();
		return new ChannelHandler[] {
			messageEncoder,
			new NettyMessage.NettyMessageDecoder(!creditBasedEnabled, causalLogManager),
			networkClientHandler};
	}

}
