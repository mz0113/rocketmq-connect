/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.rocketmq.connect.runtime.store;

import io.openmessaging.connector.api.PositionStorageReader;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apache.rocketmq.connect.runtime.service.PositionManagementService;

public class PositionStorageReaderImpl implements PositionStorageReader {

    private PositionManagementService positionManagementService;

    public PositionStorageReaderImpl(PositionManagementService positionManagementService) {

        this.positionManagementService = positionManagementService;
    }

    @Override
    public ByteBuffer getPosition(ByteBuffer partition) {
        //这个positionManagementService有2个实现类，一个是position一个是offset
        return positionManagementService.getPositionTable().get(partition);
    }

    @Override
    public Map<ByteBuffer, ByteBuffer> getPositions(Collection<ByteBuffer> partitions) {

        Map<ByteBuffer, ByteBuffer> result = new HashMap<>();
        Map<ByteBuffer, ByteBuffer> allData = positionManagementService.getPositionTable();
        for (ByteBuffer key : partitions) {
            if (allData.containsKey(key)) {
                result.put(key, allData.get(key));
            }
        }
        return result;
    }
}
