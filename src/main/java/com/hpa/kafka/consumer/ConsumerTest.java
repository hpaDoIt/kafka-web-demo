package com.hpa.kafka.consumer;

import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Title :
 * <p/>
 * Description :
 * <p/>
 * CopyRight : CopyRight (c) 2014
 * <p/>
 * <p/>
 * JDK Version Used : JDK 5.0 +
 * <p/>
 * Modification History	:
 * <p/>
 * <pre>NO.    Date    Modified By    Why & What is modified</pre>
 * <pre>1    15.10.22    bob        Created</pre>
 * <p/>
 *
 * @author bob
 */
public class ConsumerTest extends Thread {
    private final ConsumerConnector consumerConnector;
    private String topic;

    public static void main(String[] args) {
        ConsumerTest consumerThread = new ConsumerTest("com.ailk.bdx.cep.machine.info");
        consumerThread.start();
    }

    public ConsumerTest(String topic) {
        consumerConnector = kafka.consumer.Consumer.createJavaConsumerConnector(createConsumerConfig());
        this.topic = topic;
    }

    private static ConsumerConfig createConsumerConfig() {
        Properties properties = new Properties();
        properties.put("zookeeper.connect", "10.1.235.49:2182");
        properties.put("group.id", "cep_front_lyz");
        properties.put("zookeeper.session.timeout.ms", "400000");
        properties.put("zookeeper.sync.time.ms", "200");
        properties.put("auto.commit.interval.ms", "1000");

        return new ConsumerConfig(properties);
    }

    /**
     * If this thread was constructed using a separate
     * <code>Runnable</code> run object, then that
     * <code>Runnable</code> object's <code>run</code> method is called;
     * otherwise, this method does nothing and returns.
     * <p/>
     * Subclasses of <code>Thread</code> should override this method.
     *
     * @see #start()
     * @see #stop()
     * @see #Thread(ThreadGroup, Runnable, String)
     */
    @Override
    public void run() {
        try {
            Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
            topicCountMap.put(topic, new Integer(1));
            Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumerConnector.createMessageStreams(topicCountMap);
            KafkaStream<byte[], byte[]> stream = consumerMap.get(topic).get(0);
            ConsumerIterator<byte[], byte[]> it = stream.iterator();
            System.out.println("consumer Test");
            while (it.hasNext()) {
                System.out.println(new String(it.next().message()));

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}