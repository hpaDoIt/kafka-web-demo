package com.hpa.kafka.consumer;

import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.common.ErrorMapping;
import kafka.common.TopicAndPartition;
import kafka.javaapi.*;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.message.MessageAndOffset;

import java.nio.ByteBuffer;
import java.util.*;

public class SimpleConsumerClientDemo {
    public static void main(String args[]) {
        SimpleConsumerClientDemo example = new SimpleConsumerClientDemo();
        //最大测试读取信息数
        long maxReads = 1000000;
        //消费主题
        String topic = "com.ailk.bdx.cep.esper.result_20151112203724924550";
        //partition
        int[] partitions = {0, 1, 2};
        //broker list
        List<String> seeds = new ArrayList<String>();
        //seeds.add("10.1.48.211");
        seeds.add("10.1.253.49");
        //本地端口
        int port = 9092;
        try {
            example.run(maxReads, topic, partitions, seeds, port);
        } catch (Exception e) {
            System.out.println("Oops:" + e);
            e.printStackTrace();
        }
    }

    private List<String> m_replicaBrokers = new ArrayList<String>();

    public SimpleConsumerClientDemo() {
        m_replicaBrokers = new ArrayList<String>();
    }

    public void run(long a_maxReads, String topic, int[] partitions, List<String> a_seedBrokers, int a_port) throws Exception {
        /**
         * 找到leader broker
         */
        PartitionMetadata metadata = findLeader(a_seedBrokers, a_port, topic, partitions[0]);
        if (metadata == null) {
            System.out.println("Can't find metadata for Topic and Partition. Exiting");
            return;
        }
        if (metadata.leader() == null) {
            System.out.println("Can't find Leader for Topic and Partition. Exiting");
            return;
        }
        String leadBrokerHost = metadata.leader().host();
        int leadBrokerPort = metadata.leader().port();
        String clientName = "client-"+topic;
        System.out.println("leader broker host: " + leadBrokerHost +", port: " + leadBrokerPort);
        /**
         * 创建SimpleConsumer
         */
        SimpleConsumer consumer = new SimpleConsumer(leadBrokerHost, leadBrokerPort, 100000, 64 * 1024, clientName);
        /**
         * 读取不同partition的开始offsets，这里设置从头开始。
         */
        long readOffsets[] = new long[partitions.length];
        for(int i=0; i<readOffsets.length; i++) {
            readOffsets[i] = getLastOffset(consumer, topic, partitions[i], kafka.api.OffsetRequest.EarliestTime(), clientName);
        }

        int numErrors = 0;
        while (a_maxReads > 0) {
            if (consumer == null) {
                consumer = new SimpleConsumer(leadBrokerHost, leadBrokerPort, 100000, 64 * 1024, clientName);
            }
            /**
             * 从不同partition循环读取信息
             */
            long numRead = 0;
            for(int i=0; i<partitions.length; i++) {
                FetchRequest req = new FetchRequestBuilder()
                        .clientId(clientName)
                        .addFetch(topic, partitions[i], readOffsets[i], 100000) // Note: this fetchSize of 100000 might need to be increased if large batches are written to Kafka
                        .build();
                FetchResponse fetchResponse = consumer.fetch(req);

                if (fetchResponse.hasError()) {
                    numErrors++;
                    // Something went wrong!
                    short code = fetchResponse.errorCode(topic, partitions[i]);
                    System.out.println("Error fetching data from the Broker:" + leadBrokerHost + " Reason: " + code);
                    if (numErrors > 5) break;
                    if (code == ErrorMapping.OffsetOutOfRangeCode()) {
                        // We asked for an invalid offset. For simple case ask for the last element to reset
                        readOffsets[i] = getLastOffset(consumer, topic, partitions[i], kafka.api.OffsetRequest.LatestTime(), clientName);
                        continue;
                    }
                    consumer.close();
                    consumer = null;
                    leadBrokerHost = findNewLeader(leadBrokerHost, topic, partitions[i], leadBrokerPort);
                    continue;
                }
                numErrors = 0;

                for (MessageAndOffset messageAndOffset : fetchResponse.messageSet(topic, partitions[i])) {
                    long currentOffset = messageAndOffset.offset();
                    if (currentOffset < readOffsets[i]) {
                        System.out.println("Found an old offset: " + currentOffset + " Expecting: " + readOffsets[i]);
                        continue;
                    }
                    readOffsets[i] = messageAndOffset.nextOffset();
                    ByteBuffer payload = messageAndOffset.message().payload();

                    byte[] bytes = new byte[payload.limit()];
                    payload.get(bytes);
                    System.out.println(partitions[i]+":"+String.valueOf(messageAndOffset.offset()) + ": " + new String(bytes, "UTF-8"));
                    numRead++;
                    a_maxReads--;
                }
            }

            if (numRead == 0) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                }
            }
        }
        if (consumer != null) consumer.close();
    }

    public static long getLastOffset(SimpleConsumer consumer, String topic, int partition,
                                     long whichTime, String clientName) {
        TopicAndPartition topicAndPartition = new TopicAndPartition(topic, partition);
        Map<TopicAndPartition, PartitionOffsetRequestInfo> requestInfo = new HashMap<TopicAndPartition, PartitionOffsetRequestInfo>();
        requestInfo.put(topicAndPartition, new PartitionOffsetRequestInfo(whichTime, 2));
        kafka.javaapi.OffsetRequest request = new kafka.javaapi.OffsetRequest(
                requestInfo, kafka.api.OffsetRequest.CurrentVersion(), clientName);
        OffsetResponse response = consumer.getOffsetsBefore(request);

        if (response.hasError()) {
            System.out.println("Error fetching data Offset Data the Broker. Reason: " + response.errorCode(topic, partition));
            return 0;
        }
        long[] offsets = response.offsets(topic, partition);
        return offsets[0];
    }

    private String findNewLeader(String a_oldLeader, String a_topic, int a_partition, int a_port) throws Exception {
        for (int i = 0; i < 3; i++) {
            boolean goToSleep = false;
            PartitionMetadata metadata = findLeader(m_replicaBrokers, a_port, a_topic, a_partition);
            if (metadata == null) {
                goToSleep = true;
            } else if (metadata.leader() == null) {
                goToSleep = true;
            } else if (a_oldLeader.equalsIgnoreCase(metadata.leader().host()) && i == 0) {
                // first time through if the leader hasn't changed give ZooKeeper a second to recover
                // second time, assume the broker did recover before failover, or it was a non-Broker issue
                //
                goToSleep = true;
            } else {
                return metadata.leader().host();
            }
            if (goToSleep) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                }
            }
        }
        System.out.println("Unable to find new leader after Broker failure. Exiting");
        throw new Exception("Unable to find new leader after Broker failure. Exiting");
    }

    /**
     * 有个疑问，不同partition会在不同的leader broker上么？
     */
    private PartitionMetadata findLeader(List<String> a_seedBrokers, int a_port, String a_topic, int a_partition) {
        PartitionMetadata returnMetaData = null;
        loop:
        for (String seed : a_seedBrokers) {
            SimpleConsumer consumer = null;
            try {
                consumer = new SimpleConsumer(seed, a_port, 100000, 64 * 1024, "leaderLookup");
                List<String> topics = Collections.singletonList(a_topic);
                TopicMetadataRequest req = new TopicMetadataRequest(topics);
                kafka.javaapi.TopicMetadataResponse resp = consumer.send(req);

                List<TopicMetadata> metaData = resp.topicsMetadata();
                for (TopicMetadata item : metaData) {
                    System.out.println(item);
                    for (PartitionMetadata part : item.partitionsMetadata()) {
                        if (part.partitionId() == a_partition) {
                            returnMetaData = part;
                            break loop;
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Error communicating with Broker [" + seed + "] to find Leader for [" + a_topic
                        + ", " + a_partition + "] Reason: " + e);
            } finally {
                if (consumer != null) consumer.close();
            }
        }
        if (returnMetaData != null) {
            m_replicaBrokers.clear();
            for (kafka.cluster.Broker replica : returnMetaData.replicas()) {
                m_replicaBrokers.add(replica.host());
            }
        }
        return returnMetaData;
    }
}