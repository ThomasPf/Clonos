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
package org.apache.flink.runtime.causal;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Implements the <link>UpstreamDeterminantCache</> as a Growable Circular Array
 */
public class CircularVertexCausalLog implements VertexCausalLog {

	private static final Logger LOG = LoggerFactory.getLogger(CircularVertexCausalLog.class);
	private static final int DEFAULT_START_SIZE = 65536;
	private static final int GROWTH_FACTOR = 2;

	private byte[] array;
	//The first position of the array which has data
	private int start;
	//The first position of the array that does NOT have data
	private int end;

	private int size;

	private List<CheckpointOffset> epochStartOffsets;
	private DownstreamChannelOffset[] channelOffsets;

	private VertexId vertexBeingLogged;

	public CircularVertexCausalLog(int numDownstreamChannels, VertexId vertexBeingLogged) {
		this(DEFAULT_START_SIZE, numDownstreamChannels, vertexBeingLogged);
	}

	public CircularVertexCausalLog(int startSize, int numDownstreamChannels, VertexId vertexBeingLogged) {
		array = new byte[startSize];
		epochStartOffsets = new LinkedList<>();
		start = 0;
		end = 0;
		size = 0;

		this.vertexBeingLogged = vertexBeingLogged;

		CheckpointOffset startCheckpointOffset = new CheckpointOffset(0l, 0);
		epochStartOffsets.add(startCheckpointOffset);

		//initialized to 0s
		channelOffsets = new DownstreamChannelOffset[numDownstreamChannels];
		for (int i = 0; i < numDownstreamChannels; i++) {
			channelOffsets[i] = new DownstreamChannelOffset(startCheckpointOffset); //Initialize all downstreams to be at 0
		}
	}


	@Override
	public byte[] getDeterminants() {
		byte[] copy = new byte[size];
		circularArrayCopyOutOfLog(array, start, end - start, copy);
		return copy;
	}

	@Override
	public void appendDeterminants(byte[] determinants) {
		while (!hasSpaceFor(determinants.length))
			grow();

		circularCopyIntoLog(determinants, 0);
	}

	@Override
	public void processUpstreamVertexCausalLogDelta(VertexCausalLogDelta vertexCausalLogDelta) {
		int newDeterminantsLength = (vertexCausalLogDelta.offsetFromEpoch + vertexCausalLogDelta.rawDeterminants.length) - end;

		while (!hasSpaceFor(newDeterminantsLength))
			grow();


		LOG.info("Upstream log delta has {} new determinant bytes", newDeterminantsLength);
		if (newDeterminantsLength > 0)
			circularCopyIntoLog(vertexCausalLogDelta.rawDeterminants, vertexCausalLogDelta.rawDeterminants.length - newDeterminantsLength);

	}


	@Override
	public void notifyCheckpointBarrier(long checkpointId) {
		CheckpointOffset newOffset = new CheckpointOffset(checkpointId, end);
		epochStartOffsets.add(newOffset);
		for (DownstreamChannelOffset downstreamChannelOffset : channelOffsets) {
			downstreamChannelOffset.setEpochStart(newOffset);
			downstreamChannelOffset.setOffset(0);
		}
		//record current position, as all records pertaining to this checkpoint are going to be between end
		// and next offsets end
	}

	@Override
	public void notifyDownstreamFailure(int channel) {
		CheckpointOffset earliestNonCompletedEpoch = epochStartOffsets.get(0);
		channelOffsets[channel] = new DownstreamChannelOffset(earliestNonCompletedEpoch);
	}


	@Override
	public void notifyCheckpointComplete(long checkpointId) throws Exception {
		CheckpointOffset top;
		while (true) {
			top = epochStartOffsets.get(0);

			size -= circularDistance(start, top.offset, array.length);
			start = top.offset; //delete everything pertaining to this epoch

			if (checkpointId > top.id) // Keep the offset of the checkpoint that was just completed.
				epochStartOffsets.remove(0);
			else
				break;
		}
	}


	@Override
	public VertexCausalLogDelta getNextDeterminantsForDownstream(int channel) {
		int physicalOffset = getLatestEpochOffset().getOffset() + channelOffsets[channel].offset;
		LOG.info("Get next determinants for downstream");
		LOG.info("Latest Epoch offset: {}, channelOffset: {}", getLatestEpochOffset().getOffset(), channelOffsets[channel].offset);
		LOG.info("Circ distance: {}", circularDistance(physicalOffset, end, array.length));
		LOG.info("State of log: {}", this.toString());
		byte[] toReturn = new byte[circularDistance(physicalOffset, end, array.length)];

		circularArrayCopyOutOfLog(array, physicalOffset, end - physicalOffset, toReturn);

		int offsetToSend = channelOffsets[channel].offset;

		channelOffsets[channel].setOffset(end - getLatestEpochOffset().getOffset()); //channel has all the latest determinants

		return new VertexCausalLogDelta(vertexBeingLogged, toReturn, offsetToSend);
	}

	@Override
	public String toString() {
		return "CircularVertexCausalLog{" +
			"vertexBeingLogged=" + vertexBeingLogged +
			", start=" + start +
			", end=" + end +
			", size=" + size +
			", offsets=[" + epochStartOffsets.stream().map(Objects::toString).collect(Collectors.toList()) +"]"+
			", channelOffsets=" + Arrays.toString(channelOffsets) +
			'}';
	}

	private CheckpointOffset getLatestEpochOffset() {
		return epochStartOffsets.get(epochStartOffsets.size() - 1);
	}

	/**
	 * @param from      start point (inclusive)
	 * @param to        end point (exclusive)
	 * @param totalSize
	 * @return
	 */
	private static int circularDistance(int from, int to, int totalSize) {
		return Math.floorMod((to - from), totalSize);
	}

	private void circularArrayCopyOutOfLog(byte[] from, int start, int numBytesToCopy, byte[] to) {
		if (start + numBytesToCopy <= from.length) {
			System.arraycopy(from, start, to, 0, numBytesToCopy);
		} else { //Goes around the circle
			int numBytesToCopyBeforeCompleteCircle = from.length - start;
			System.arraycopy(from, start, to, 0, numBytesToCopyBeforeCompleteCircle);
			System.arraycopy(from, start, to, numBytesToCopyBeforeCompleteCircle, numBytesToCopy - numBytesToCopyBeforeCompleteCircle);
		}
	}

	/**
	 * Grows the array and moves everything to position 0
	 */
	private void grow() {
		byte[] newArray = new byte[array.length * GROWTH_FACTOR];

		circularArrayCopyOutOfLog(array, start, size, newArray);
		int move = -start;
		start = 0;
		end = size;

		// Since we reset to 0, we need to move the offsets as well.
		for (CheckpointOffset offset : epochStartOffsets) {
			offset.setOffset((offset.offset + move) % array.length);
		}

		array = newArray;
	}


	/**
	 * PRECONDITION: has enough space!
	 *
	 * @param determinants
	 * @param offset
	 */
	private void circularCopyIntoLog(byte[] determinants, int offset) {
		int numDeterminantsToCopy = determinants.length - offset;
		int bytesTillLoop = array.length - end;
		if (numDeterminantsToCopy > bytesTillLoop) {
			System.arraycopy(determinants, offset, array, end, bytesTillLoop);
			System.arraycopy(determinants, offset + bytesTillLoop, array, 0, numDeterminantsToCopy - bytesTillLoop);
		} else {
			System.arraycopy(determinants, offset, array, end, numDeterminantsToCopy);
		}
		end = (end + numDeterminantsToCopy) % array.length;
		size += numDeterminantsToCopy;
	}

	private boolean hasSpaceFor(int toAdd) {
		return array.length - size >= toAdd;
	}

	private class CheckpointOffset {
		//The checkpoint id that initiates this epoch
		private long id;
		//The physical offset in the circular log of the first element after the checkpoint
		private int offset;

		public CheckpointOffset(long id, int offset) {
			this.id = id;
			this.offset = offset;
		}

		public long getId() {
			return id;
		}

		public void setId(long id) {
			this.id = id;
		}

		public int getOffset() {
			return offset;
		}

		public void setOffset(int offset) {
			this.offset = offset;
		}

		@Override
		public String toString() {
			return "CheckpointOffset{" +
				"id=" + id +
				", offset=" + offset +
				'}';
		}
	}

	/**
	 * Marks the next element to be read by the downstream consumer
	 */
	private class DownstreamChannelOffset {
		// Refers to the checkpoint that starts the epoch the downstream is currently processing
		private CheckpointOffset epochStart;

		// The logical offset from that epoch
		private int offset;

		public DownstreamChannelOffset(CheckpointOffset epochStart) {
			this.epochStart = epochStart;
			this.offset = 0;
		}

		public CheckpointOffset getEpochStart() {
			return epochStart;
		}

		public void setEpochStart(CheckpointOffset epochStart) {
			this.epochStart = epochStart;
		}

		public int getOffset() {
			return offset;
		}

		public void setOffset(int offset) {
			this.offset = offset;
		}

		@Override
		public String toString() {
			return "DownstreamChannelOffset{" +
				"epochStart=" + epochStart +
				", offset=" + offset +
				'}';
		}
	}

}