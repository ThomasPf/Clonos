/*
 *
 *
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 *
 *
 */

package org.apache.flink.runtime.causal;

import org.apache.flink.shaded.netty4.io.netty.buffer.ByteBuf;
import org.apache.flink.shaded.netty4.io.netty.buffer.CompositeByteBuf;
import org.apache.flink.shaded.netty4.io.netty.buffer.Unpooled;
import org.junit.Test;

public class NettyTests {

	@Test
	public void CompositeByteBufferTest() {

		ByteBuf b1 = Unpooled.buffer();

		b1.writeBytes("Hello ".getBytes());


		ByteBuf b2 = Unpooled.buffer();
		b2.writeBytes("world".getBytes());

		CompositeByteBuf cb = Unpooled.compositeBuffer();
		cb.addComponents(true, b1, b2);

		byte[] bytes = new byte[cb.readableBytes()];
		cb.readBytes(bytes);
		assert b1.readerIndex() == 0;
		assert new String(bytes).equals("Hello world");

		CompositeByteBuf cb2 = Unpooled.compositeBuffer();
		cb2.addComponents(true, b1, b2);
		byte[] bytes2 = new byte[cb2.readableBytes()];
		cb.readBytes(bytes2);
		assert new String(bytes2).equals("Hello world");
	}

	@Test
	public void WrappedTest() {

		ByteBuf b1 = Unpooled.buffer();
		b1.writeBytes("Hello ".getBytes());


		ByteBuf b2 = Unpooled.buffer();
		b2.writeBytes("world".getBytes());

		ByteBuf wrapped1 = Unpooled.wrappedBuffer(b1, b2);
		byte[] bytes1 = new byte[wrapped1.readableBytes()];
		wrapped1.readBytes(bytes1);
		assert b1.readerIndex() == 0;
		assert new String(bytes1).equals("Hello world");

		ByteBuf wrapped2 = Unpooled.wrappedBuffer(b1, b2);
		byte[] bytes2 = new byte[wrapped2.readableBytes()];
		wrapped2.readBytes(bytes2);
		assert b1.readerIndex() == 0;
		assert new String(bytes2).equals("Hello world");

	}
}
