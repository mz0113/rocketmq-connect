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

package org.apache.rocketmq.connect.runtime.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.rocketmq.connect.runtime.common.ConnectorAndTaskConfigs;
import org.apache.rocketmq.connect.runtime.common.ConnectKeyValue;

public class TransferUtils {

    public static String keyValueToString(ConnectKeyValue keyValue) {

        Map<String, String> resMap = new HashMap<>();
        if (null == keyValue) {
            return JSON.toJSONString(resMap);
        }
        for (String key : keyValue.keySet()) {
            resMap.put(key, keyValue.getString(key));
        }
        return JSON.toJSONString(resMap);
    }

    public static String keyValueListToString(List<ConnectKeyValue> keyValueList) {

        List<Map<String, String>> resList = new ArrayList<>();
        if (null == keyValueList || 0 == keyValueList.size()) {
            return JSON.toJSONString(resList);
        }
        for (ConnectKeyValue keyValue : keyValueList) {
            Map<String, String> resMap = new HashMap<>();
            for (String key : keyValue.keySet()) {
                resMap.put(key, keyValue.getString(key));
            }
            resList.add(resMap);
        }
        return JSON.toJSONString(resList);
    }

    public static ConnectKeyValue stringToKeyValue(String json) {

        if (null == json) {
            return new ConnectKeyValue();
        }
        Map<String, String> map = JSON.parseObject(json, Map.class);
        ConnectKeyValue keyValue = new ConnectKeyValue();
        for (String key : map.keySet()) {
            keyValue.put(key, map.get(key));
        }
        return keyValue;
    }

    public static List<ConnectKeyValue> stringToKeyValueList(String json) {

        List<ConnectKeyValue> resultList = new ArrayList<>();
        if (null == json) {
            return resultList;
        }
        List<Map<String, String>> list = JSON.parseObject(json, List.class);
        for (Map<String, String> map : list) {
            ConnectKeyValue keyValue = new ConnectKeyValue();
            for (String key : map.keySet()) {
                keyValue.put(key, map.get(key));
            }
            resultList.add(keyValue);
        }
        return resultList;
    }

    public static String toJsonString(Map<String, String> connectorConfigs, Map<String, String> taskConfigs) {

        JSONObject jsonObject = new JSONObject();
        jsonObject.put("connector", connectorConfigs);
        jsonObject.put("task", taskConfigs);
        return jsonObject.toString();
    }

    public static ConnectorAndTaskConfigs toConnAndTaskConfigs(String json) {

        JSONObject jsonObject = JSON.parseObject(json, JSONObject.class);

        Map<String, String> connectorConfigs = (Map<String, String>) jsonObject.getObject("connector", Map.class);
        Map<String, String> taskConfigs = (Map<String, String>) jsonObject.getObject("task", Map.class);

        Map<String, ConnectKeyValue> transferedConnectorConfigs = new HashMap<>();
        for (String key : connectorConfigs.keySet()) {
            transferedConnectorConfigs.put(key, stringToKeyValue(connectorConfigs.get(key)));
        }
        Map<String, List<ConnectKeyValue>> transferedTasksConfigs = new HashMap<>();
        for (String key : taskConfigs.keySet()) {
            transferedTasksConfigs.put(key, stringToKeyValueList(taskConfigs.get(key)));
        }

        ConnectorAndTaskConfigs res = new ConnectorAndTaskConfigs();
        res.setConnectorConfigs(transferedConnectorConfigs);
        res.setTaskConfigs(transferedTasksConfigs);
        return res;
    }

}
