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

package org.apache.flink.util;


import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import org.apache.flink.util.AbstractID;

public class TupleId {
    static final TupleId unanchoredMsgId = makeId(Collections.emptyMap());

    private Map<Long, Long> anchorsToIds;

    protected TupleId(Map<Long, Long> anchorsToIds) {
        this.anchorsToIds = anchorsToIds;
    }

    public static long generateId(Random rand) {
        return rand.nextLong();
    }

    public static TupleId makeUnanchored() {
        return unanchoredMsgId;
    }

    public static TupleId makeId(Map<Long, Long> anchorsToIds) {
        return new TupleId(anchorsToIds);
    }

    public static TupleId makeRootId(long id, long val) {
        Map<Long, Long> anchorsToIds = new HashMap<>();
        anchorsToIds.put(id, val);
        return new TupleId(anchorsToIds);
    }

    public static TupleId deserialize(Input in) throws IOException {
        int numAnchors = in.readInt(true);
        Map<Long, Long> anchorsToIds = new HashMap<>();
        for (int i = 0; i < numAnchors; i++) {
            anchorsToIds.put(in.readLong(), in.readLong());
        }
        return new TupleId(anchorsToIds);
    }

    public Map<Long, Long> getAnchorsToIds() {
        return anchorsToIds;
    }

    public Set<Long> getAnchors() {
        return anchorsToIds.keySet();
    }

    @Override
    public int hashCode() {
        return anchorsToIds.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TupleId && anchorsToIds.equals(((TupleId) other).anchorsToIds);
    }

    @Override
    public String toString() {
        return anchorsToIds.toString();
    }

    public void serialize(Output out) throws IOException {
        out.writeInt(anchorsToIds.size(), true);
        for (Entry<Long, Long> anchorToId : anchorsToIds.entrySet()) {
            out.writeLong(anchorToId.getKey());
            out.writeLong(anchorToId.getValue());
        }
    }
}
