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
package org.apache.flink.runtime.causal.services;

import org.apache.flink.api.common.services.RandomService;
import org.apache.flink.runtime.causal.EpochProvider;
import org.apache.flink.runtime.causal.log.job.IJobCausalLog;
import org.apache.flink.runtime.causal.determinant.RNGDeterminant;
import org.apache.flink.runtime.causal.recovery.IRecoveryManager;
import org.apache.flink.util.XORShiftRandom;


public class CausalRandomService implements RandomService {

	private IJobCausalLog causalLoggingManager;
	private IRecoveryManager recoveryManager;
	private EpochProvider epochProvider;

	//Not thread safe
	protected final XORShiftRandom rng = new XORShiftRandom();

	private RNGDeterminant reuseRNGDeterminant;

	public CausalRandomService(IJobCausalLog causalLoggingManager, IRecoveryManager recoveryManager, EpochProvider epochProvider) {
		this.causalLoggingManager = causalLoggingManager;
		this.recoveryManager = recoveryManager;
		this.epochProvider = epochProvider;
		this.reuseRNGDeterminant = new RNGDeterminant();
	}

	@Override
	public int nextInt() {
		return this.nextInt(Integer.MAX_VALUE);
	}

	@Override
	public int nextInt(int maxExclusive) {
		if(recoveryManager.isReplaying())
			 return  recoveryManager.replayRandomInt();

		int generatedNumber = rng.nextInt(maxExclusive);
		causalLoggingManager.appendDeterminant(reuseRNGDeterminant.replace(generatedNumber),epochProvider.getCurrentEpochID());
		return generatedNumber;
	}

}