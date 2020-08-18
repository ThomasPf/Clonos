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

package org.apache.flink.runtime.causal.log.vertex;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.causal.VertexGraphInformation;
import org.apache.flink.runtime.causal.VertexID;
import org.apache.flink.runtime.causal.determinant.Determinant;
import org.apache.flink.runtime.causal.determinant.DeterminantEncoder;
import org.apache.flink.runtime.causal.log.thread.*;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.buffer.BufferPool;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannelID;
import org.apache.flink.runtime.jobgraph.IntermediateDataSet;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class BasicLocalVertexCausalLog implements LocalVertexCausalLog {

	private static final Logger LOG = LoggerFactory.getLogger(BasicLocalVertexCausalLog.class);

	VertexID vertexId;

	//SPMC
	LocalThreadCausalLog mainThreadLog;

	//SPSC
	Map<IntermediateResultPartitionID, LocalThreadCausalLog[]> subpartitionLogs;


	Map<InputChannelID, Tuple2<IntermediateResultPartitionID, Integer>> consumerPartitions;

	DeterminantEncoder encoder;

	public BasicLocalVertexCausalLog(VertexGraphInformation vertexGraphInformation,
									 ResultPartitionWriter[] resultPartitionsOfLocalVertex, BufferPool bufferPool,
									 DeterminantEncoder encoder) {
		this.encoder = encoder;

		int totalSubpartitions =
			vertexGraphInformation.getJobVertex().getProducedDataSets().stream().map(IntermediateDataSet::getConsumers)
				.mapToInt(List::size).sum();
		subpartitionLogs = new ConcurrentHashMap<>(totalSubpartitions);

		this.vertexId = vertexGraphInformation.getThisTasksVertexID();
		this.mainThreadLog = new NetworkBufferBasedContiguousLocalThreadCausalLog(bufferPool, encoder);

		for (ResultPartitionWriter resultPartition : resultPartitionsOfLocalVertex) {
			LocalThreadCausalLog[] threadCausalLogs =
				new LocalThreadCausalLog[resultPartition.getNumberOfSubpartitions()];
			for (int i = 0; i < resultPartition.getNumberOfSubpartitions(); i++)
				threadCausalLogs[i] = new NetworkBufferBasedContiguousLocalThreadCausalLog(bufferPool, encoder);

			subpartitionLogs.put(resultPartition.getPartitionId().getPartitionId(), threadCausalLogs);
		}

		consumerPartitions = new ConcurrentHashMap<>();
	}

	@Override
	public void appendDeterminant(Determinant determinant, long epochID) {
		LOG.debug("Appending determinants to main thread, epochID {}.", epochID);
		mainThreadLog.appendDeterminant(determinant, epochID);
	}

	@Override
	public void appendSubpartitionDeterminants(Determinant determinant, long epochID,
											   IntermediateResultPartitionID intermediateResultPartitionID,
											   int subpartitionIndex) {
		LOG.debug("Appending determinant for epochID {} to intermediateResultPartitionID {} subpartition {}", epochID,
			intermediateResultPartitionID, subpartitionIndex);
		subpartitionLogs.get(intermediateResultPartitionID)[subpartitionIndex].appendDeterminant(determinant, epochID);
	}

	@Override
	public void registerDownstreamConsumer(InputChannelID inputChannelID,
										   IntermediateResultPartitionID intermediateResultPartitionID,
										   int consumedSubpartition) {
		LOG.debug("Registering downstream consumer at local vertex level");

		consumerPartitions.put(inputChannelID, new Tuple2<>(intermediateResultPartitionID, consumedSubpartition));
		//only register this consumer with his specific subpartition
		//He is not causally affected by the state of the other subpartitions
	}

	@Override
	public void unregisterDownstreamConsumer(InputChannelID toCancel) {
		consumerPartitions.remove(toCancel);
	}

	@Override
	public VertexCausalLogDelta getDeterminants(long startEpochID) {
		throw new UnsupportedOperationException("For a local log, you should request determinants providing a consumer" +
			" id");
	}


	@Override
	public VertexCausalLogDelta getNextDeterminantsForDownstream(InputChannelID consumer, long checkpointID) {
		LOG.debug("Getting next determinants for downstream.");
		ThreadLogDelta mainThreadLogDelta = mainThreadLog.getNextDeterminantsForDownstream(consumer, checkpointID);

		//Only grab partition updates which causally affect this consumer
		Tuple2<IntermediateResultPartitionID, Integer> consumerPartition = consumerPartitions.get(consumer);
		ThreadLogDelta sLogDelta =
			subpartitionLogs.get(consumerPartition.f0)[consumerPartition.f1].getNextDeterminantsForDownstream(consumer
				, checkpointID);
		SubpartitionThreadLogDelta subpartitionLogDelta = new SubpartitionThreadLogDelta(sLogDelta,
			consumerPartition.f1);
		return new VertexCausalLogDelta(this.vertexId,
			(mainThreadLogDelta.getDeltaSize() > 0 ? mainThreadLogDelta : null),
			(subpartitionLogDelta.getDeltaSize() > 0 ? ImmutableSortedMap.of(consumerPartition.f0,
				ImmutableSortedMap.of(consumerPartition.f1, subpartitionLogDelta)) : Collections.emptySortedMap()));
	}

	@Override
	public void notifyCheckpointComplete(long checkpointID) {
		try {
			mainThreadLog.notifyCheckpointComplete(checkpointID);
			for (LocalThreadCausalLog localThreadCausalLog :
				subpartitionLogs.values().stream().flatMap(Arrays::stream).collect(Collectors
				.toList()))
				localThreadCausalLog.notifyCheckpointComplete(checkpointID);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public int mainThreadLogLength() {
		return mainThreadLog.logLength();
	}

	@Override
	public int subpartitionLogLength(IntermediateResultPartitionID intermediateResultPartitionID,
									 int subpartitionIndex) {
		return this.subpartitionLogs.get(intermediateResultPartitionID)[subpartitionIndex].logLength();
	}

	@Override
	public void close() {
		mainThreadLog.close();
		for(LocalThreadCausalLog[] partitionLogs: subpartitionLogs.values())
			for(LocalThreadCausalLog subpartitionLog : partitionLogs)
				subpartitionLog.close();
	}

	@Override
	public String toString() {
		return "BasicLocalVertexCausalLog{" +
			"vertexId=" + vertexId +
			", mainThreadLog=" + mainThreadLog +
			'}';
	}

}