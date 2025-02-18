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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.connect.kafka.connector;

import io.netty.util.concurrent.DefaultThreadFactory;
import io.openmessaging.KeyValue;
import io.openmessaging.connector.api.data.*;
import io.openmessaging.connector.api.source.SourceTask;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.rocketmq.connect.kafka.config.ConfigDefine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 *  连接器实例属于逻辑概念，其负责维护特定数据系统的相关配置，比如链接地址、需要同步哪些数据等信息；
 *  在connector 实例被启动后，connector可以根据配置信息，对解析任务进行拆分，分配出task。这么做的目的是为了提高并行度，提升处理效率
 *  注意因为类加载器的缘故，基本上这里都不允许异步的方法
 *
 *  从kafka中poll下来数据后，需要提交kafka的位移，而这里使用的是自动提交位移机制 enable.auto.commit = true
 */
public class KafkaSourceTask extends SourceTask {

    private static DefaultThreadFactory defaultThreadFactory = new DefaultThreadFactory("kafkaOffsetCommit-");
    private static Logger logger4SourceMsg = LoggerFactory.getLogger("logger4SourceMsg");

    private static final Logger log = LoggerFactory.getLogger(SourceTask.class);
    private KafkaConsumer<ByteBuffer, ByteBuffer> consumer;
    private KeyValue config;
    private List<String> topicList;
    //这currentTPList 线程不安全，重平衡发生时候是并发的
    private final Set<TopicPartition> currentTPList = new CopyOnWriteArraySet<>();
    private static final byte[] TRUE_BYTES = "true".getBytes(StandardCharsets.UTF_8);
    //启动定时任务提交位移
    private MyOffsetCommitCallback commitCallback =  new MyOffsetCommitCallback();

    private long nextCommitstamp = 0l;
    private long commitInterval = 5000;
    @Override
    public Collection<SourceDataEntry> poll() {
        try {

            ConsumerRecords<ByteBuffer, ByteBuffer> records = null;

            //注意consumer非线程安全，所以提交位移时候的定时线程会导致consumer报错

            if (nextCommitstamp==0l || System.currentTimeMillis() > nextCommitstamp) {
                commitOffset(currentTPList);
                nextCommitstamp = System.currentTimeMillis() + commitInterval;
            }

            try{
                overridePositionOffset();
            }catch (Exception ex){
                log.warn(ex.getMessage(),ex);
                return null;
            }

/*
[INFO ] 22-04-16 14:37:36,248 [WorkTask-Executor--2-1             ] [RocketMQRuntime                    ] putPosition 28236
[INFO ] 22-04-16 14:37:36,248 [WorkTask-Executor--2-1             ] [o.a.r.c.k.c.KafkaSourceTask        ] seek offset 28237
[INFO ] 22-04-16 14:37:36,249 [WorkTask-Executor--2-1             ] [o.a.k.c.c.i.ConsumerCoordinator    ] Revoking previously assigned partitions [kafkaconnect-0] for group connector-consumer-group
[INFO ] 22-04-16 14:37:36,249 [WorkTask-Executor--2-1             ] [o.a.r.c.k.c.KafkaSourceTask        ] onPartitionsRevoked Partitions revoked WorkTask-Executor--2-1,[kafkaconnect-0]
[INFO ] 22-04-16 14:37:36,250 [WorkTask-Executor--2-1             ] [o.a.k.c.c.i.AbstractCoordinator    ] (Re-)joining group connector-consumer-group
[INFO ] 22-04-16 14:37:36,253 [WorkTask-Executor--2-1             ] [o.a.k.c.c.i.AbstractCoordinator    ] Successfully joined group connector-consumer-group with generation 18
[INFO ] 22-04-16 14:37:36,255 [WorkTask-Executor--2-1             ] [o.a.k.c.c.i.ConsumerCoordinator    ] Setting newly assigned partitions [kafkaconnect-0] for group connector-consumer-group
[INFO ] 22-04-16 14:37:36,255 [WorkTask-Executor--2-1             ] [o.a.r.c.k.c.KafkaSourceTask        ] onPartitionsAssigned Partitions [kafkaconnect-0]
[INFO ] 22-04-16 14:37:36,266 [WorkTask-Executor--2-1             ] [RocketMQRuntime                    ] Successful send message to RocketMQ: kafka offset:kafkaconnect-0:28236,rocketMQ msgID:C0A80268194818B4AAC25062DC846E54
 */

            //上面28236重新消费了一次，也就是说，如果在poll的时候发生了重平衡,可能会重复消费，所以要在重平衡里面也set最新位移

            records = consumer.poll(2000);
            ArrayList<SourceDataEntry> entries = new ArrayList<>(records.count());

            for (ConsumerRecord<ByteBuffer, ByteBuffer> record : records) {
                String topic_partition = record.topic() + "-" + record.partition();
                //header
                Headers headers = record.headers();
                Map<String, byte[]> map = new HashMap<>(4);
                if (headers!=null) {
                    for (Header header : headers) {
                        String key = header.key();
                        byte[] value = header.value();
                        //这里会把kafka header中的by_connector也放入，如果有的话。后续会判断然后skip掉这条msg
                        map.put(key,value);
                    }
                }

                //
                //map.put("by_connector",TRUE_BYTES);

                Schema schema = new Schema();
                List<Field> fields = new ArrayList<>();
                fields.add(new Field(0, "key", FieldType.BYTES));
                fields.add(new Field(1, "value", FieldType.BYTES));
                fields.add(new Field(2, "header", FieldType.MAP));
                schema.setName(record.topic());
                schema.setFields(fields);
                schema.setDataSource(record.topic());

                ByteBuffer sourcePartition = ByteBuffer.wrap(topic_partition.getBytes());
                ByteBuffer sourcePosition = ByteBuffer.wrap(String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8));

                //把kafka record构建为dataEntryBuilder
                DataEntryBuilder dataEntryBuilder = new DataEntryBuilder(schema);
                dataEntryBuilder.entryType(EntryType.CREATE);
                dataEntryBuilder.queue(record.topic()); //queueName will be set to RocketMQ topic by runtime
                dataEntryBuilder.timestamp(System.currentTimeMillis());

                //key
                if (record.key() != null) {
                    dataEntryBuilder.putFiled("key", record.key().array());
                } else {
                    dataEntryBuilder.putFiled("key", null);
                }

                //value
                dataEntryBuilder.putFiled("value", record.value().array());

                //header
                dataEntryBuilder.putFiled("header",map);

                //sourcePartition = topic_partition ,即topic+分区
                //sourcePosition = record.offset()
                SourceDataEntry entry = dataEntryBuilder.buildSourceDataEntry(sourcePartition, sourcePosition);
                entries.add(entry);
            }

            return entries;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("poll exception {}", e);
        }
        return null;
    }

    private void overridePositionOffset() {
        for (TopicPartition tp : currentTPList) {
            final ByteBuffer topicBuffer = ByteBuffer.wrap((tp.topic() + "-" + tp.partition()).getBytes());

            final ByteBuffer position = context.positionStorageReader().getPosition(topicBuffer);
            if (position == null) {
                //do nothing
            } else {
                //发送到rocketMQ成功后会更新消费位移,那边也是同步块代码,等又来到这边以后,正常情况下位移都提交了
                long local_offset = Long.parseLong(new String(position.array()));
                try{
                    consumer.seek(tp, local_offset+1);
                    logger4SourceMsg.info(String.format("kafka consumer seek offset %s:%s", tp, local_offset + 1));
                }catch (Exception ex){
                    throw new RuntimeException("consumer seek offset failed may be the partition have not belong to this consumer ,and will try again..",ex);
                }
            }
        }
    }

    @Override
    public void start(KeyValue taskConfig) {
        log.info("source task start enter");
        this.topicList = new ArrayList<>();
        this.config = taskConfig;
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.config.getString(ConfigDefine.BOOTSTRAP_SERVER));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, this.config.getString(ConfigDefine.GROUP_ID));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 150);
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteBufferDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteBufferDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        this.consumer = new KafkaConsumer<>(props);

        String topics = this.config.getString(ConfigDefine.TOPICS);
        for (String topic : topics.split(",")) {
            if (!topic.isEmpty()) {
                topicList.add(topic);
            }
        }

        consumer.subscribe(topicList, new MyRebalanceListener());
        log.info("source task subscribe topicList {}", topicList);
    }

    @Override
    public void stop() {
        log.info("source task stop enter");
        try {

            try{
                commitOffset(new HashSet<>(currentTPList));
            }catch (Exception ex){
                log.error("commit kafka Offset failed when stop",ex);
            }

            consumer.wakeup(); // wakeup poll in other thread
            consumer.close();
        } catch (Exception e) {
            log.warn("{} consumer {} close exception {}", this, consumer, e);
        }
    }

    @Override
    public void pause() {
        log.info("source task pause ...");
        consumer.pause(currentTPList);
    }

    @Override
    public void resume() {
        log.info("source task resume ...");
        consumer.resume(currentTPList);
    }

    public String toString() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        String pid = name.split("@")[0];
        return "KafkaSourceTask-PID[" + pid + "]-" + Thread.currentThread().toString();
    }

    public static TopicPartition getTopicPartition(ByteBuffer buffer)
    {
        Charset charset = null;
        CharsetDecoder decoder = null;
        CharBuffer charBuffer = null;
        try
        {
            charset = Charset.forName("UTF-8");
            decoder = charset.newDecoder();
            charBuffer = decoder.decode(buffer.asReadOnlyBuffer());
            String topic_partition = charBuffer.toString();
            int index = topic_partition.lastIndexOf('-');
            if (index != -1 && index > 1) {
                String topic = topic_partition.substring(0, index);
                int partition = Integer.parseInt(topic_partition.substring(index + 1));
                return new TopicPartition(topic, partition);
            }
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
            log.warn("getString Exception {}", ex);
        }
        return null;
    }

    /**
     * 这里提交kafka偏移量，只提交发rocketMQ发送成功收到回调success的那些消息，也就是说消费位移是context.positionStorageReader()读出来的
     * 其实就是 positionManagementService.getPositionTable().get(partition) ，而这个positionManagementService就是在rocketMQ消息发出去收到回调才会put进去
     * 而且这个提交位移好像不是定时提交，只在stop和发生重平衡才会提交位移。这个问题不是会很大吗。不会,已经 ENABLE_AUTO_COMMIT_CONFIG 也就是自动你提交参数打开了
     * TODO kafka的自动位移提交应该不行，因为未必发送到rocketMQ是成功的，所以消费位移也得以rocketMQ发送出去的消息的位移为准
     * @param tpList
     */
    private void commitOffset(Collection<TopicPartition> tpList) {
        if(tpList == null || tpList.isEmpty())
            return;
        List<ByteBuffer> topic_partition_list = new ArrayList<>();
        for (TopicPartition tp : tpList) {
            //如果重平衡正好发生，此时正在迭代。是不是有问题？所以改成了copyOnWriteArrayList
            topic_partition_list.add(ByteBuffer.wrap((tp.topic() + "-" + tp.partition()).getBytes()));
        }

        Map<TopicPartition, OffsetAndMetadata> commitOffsets = new HashMap<>();
        Map<ByteBuffer, ByteBuffer> topic_position_map = context.positionStorageReader().getPositions(topic_partition_list);
        if (topic_position_map==null || topic_position_map.size()==0) {
            return;
        }
        for (Map.Entry<ByteBuffer, ByteBuffer> entry : topic_position_map.entrySet()) {
            TopicPartition tp = getTopicPartition(entry.getKey());
            if (tp != null && tpList.contains(tp)) {
                //positionStorage store more than this task's topic and partition
                try {
                    long local_offset = Long.parseLong(new String(entry.getValue().array()));
                    commitOffsets.put(tp, new OffsetAndMetadata(local_offset));
                } catch (Exception e) {
                    log.warn("commit kafka Offset get local offset exception {}", e);
                }
            }
        }

        if (!commitOffsets.isEmpty()) {
            try{
                Map map = new HashMap();
                for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : commitOffsets.entrySet()) {
                    map.put(entry.getKey(),entry.getValue());
                    consumer.commitSync(map);
                }
                consumer.commitSync(commitOffsets);
            }catch (Exception ex){
                commitCallback.onComplete(commitOffsets,ex);
            }
        }
    }

    private class MyOffsetCommitCallback implements OffsetCommitCallback {

        @Override
        public void onComplete(Map<TopicPartition, OffsetAndMetadata> map, Exception e) {
            if (e != null) {
                map.entrySet().stream().forEach((Map.Entry<TopicPartition, OffsetAndMetadata> entry) -> {
                    logger4SourceMsg.warn("commit kafka Offset exception, TopicPartition: {} offset: {}", entry.getKey().toString(), entry.getValue().offset());
                });
                logger4SourceMsg.error("commit kafka offset error ",e);
            }else{
                map.entrySet().stream().forEach((Map.Entry<TopicPartition, OffsetAndMetadata> entry) -> {
                    logger4SourceMsg.warn("commit kafka Offset finish, TopicPartition: {} offset: {}", entry.getKey().toString(), entry.getValue().offset());
                });
            }
        }
    }

    private class MyRebalanceListener implements ConsumerRebalanceListener {

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            currentTPList.addAll(partitions);
            try{
                //重置位移
                overridePositionOffset();
            }catch (Exception ex){
                log.warn(ex.getMessage(),ex);
            }
            log.info("onPartitionsAssigned Partitions {}",partitions);
        }

        /**
         * 当kafka分区不再分配给自己的时候，需要提交kafka的消费位移
         * @param partitions
         */
        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            log.info("onPartitionsRevoked Partitions revoked {},{}",Thread.currentThread().getName(),partitions);
            try{
                commitOffset(partitions);
            }catch (Exception ex){
                log.error("commit kafka Offset when onPartitionsRevoked Partitions revoked failed",ex);
            }finally {
                currentTPList.clear();
            }
        }
    }
}
