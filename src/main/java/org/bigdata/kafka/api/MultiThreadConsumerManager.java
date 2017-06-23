package org.bigdata.kafka.api;

import org.apache.commons.collections.map.HashedMap;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.bigdata.kafka.multithread.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Created by hjq on 2017/6/19.
 */
public class MultiThreadConsumerManager {
    private static Logger log = LoggerFactory.getLogger(MultiThreadConsumerManager.class);
    private static final MultiThreadConsumerManager manager = new MultiThreadConsumerManager();
    private Map<String, MessageFetcher> name2Fetcher = new HashedMap();

    public static MultiThreadConsumerManager instance(){
        return manager;
    }

    private MultiThreadConsumerManager() {
    }

    private void checkAppName(String appName){
        if (name2Fetcher.containsKey(appName)){
            throw new IllegalStateException("Manager has same app name");
        }
    }

    /**
     * 该方法不会自动启动MessageFetcher线程
     * 启动操作由使用者完成
     * @param appName
     * @param properties
     * @param <K>
     * @param <V>
     * @return
     */
    public <K, V> MessageFetcher<K, V> registerConsumer(String appName,
                                                        Properties properties){
        checkAppName(appName);
        MessageFetcher<K, V> instance = new MessageFetcher<K, V>(properties);
        name2Fetcher.put(appName, instance);
        return instance;
    }

    /**
     * 该方法会自动启动MessageFetcher线程
     * @param appName
     * @param properties
     * @param topics
     * @param listener
     * @param <K>
     * @param <V>
     * @return
     */
    public <K, V> MessageFetcher<K, V> registerConsumer(String appName,
                                                        Properties properties,
                                                        Collection<String> topics,
                                                        ConsumerRebalanceListener listener,
                                                        Map<String, MessageHandler> topic2Handler,
                                                        Map<String, CommitStrategy> topic2CommitStrategy){
        checkAppName(appName);
        MessageFetcher<K, V> messageFetcher = new MessageFetcher<>(properties);
        if(listener != null){
            messageFetcher.subscribe(topics, listener);
        }
        else{
            messageFetcher.subscribe(topics, messageFetcher.new InMemoryRebalanceListsener());
        }
        messageFetcher.registerHandlers(topic2Handler);
        messageFetcher.registerCommitStrategies(topic2CommitStrategy);
        name2Fetcher.put(appName, messageFetcher);
        startConsume(messageFetcher);
        return  messageFetcher;
    }

    /**
     * 该方法会自动启动MessageFetcher线程
     * @param appName
     * @param properties
     * @param topics
     * @param <K>
     * @param <V>
     * @return
     */
    public <K, V> MessageFetcher<K, V> registerConsumer(String appName,
                                                        Properties properties,
                                                        Collection<String> topics,
                                                        Map<String, MessageHandler> topic2Handler,
                                                        Map<String, CommitStrategy> topic2CommitStrategy){
        checkAppName(appName);
        MessageFetcher<K, V> messageFetcher = new MessageFetcher<>(properties);
        messageFetcher.subscribe(topics);
        messageFetcher.registerHandlers(topic2Handler);
        messageFetcher.registerCommitStrategies(topic2CommitStrategy);
        name2Fetcher.put(appName, messageFetcher);
        startConsume(messageFetcher);
        return  messageFetcher;
    }

    /**
     * 该方法会自动启动MessageFetcher线程
     * @param appName
     * @param properties
     * @param pattern
     * @param listener
     * @param <K>
     * @param <V>
     * @return
     */
    public <K, V> MessageFetcher<K, V> registerConsumer(String appName,
                                                        Properties properties,
                                                        Pattern pattern,
                                                        ConsumerRebalanceListener listener,
                                                        Map<String, MessageHandler> topic2Handler,
                                                        Map<String, CommitStrategy> topic2CommitStrategy) {
        checkAppName(appName);
        MessageFetcher<K, V> messageFetcher = new MessageFetcher<>(properties);
        if(listener != null){
            messageFetcher.subscribe(pattern, listener);
        }
        else{
            messageFetcher.subscribe(pattern, messageFetcher.new InMemoryRebalanceListsener());
        }
        messageFetcher.registerHandlers(topic2Handler);
        messageFetcher.registerCommitStrategies(topic2CommitStrategy);
        name2Fetcher.put(appName, messageFetcher);
        startConsume(messageFetcher);
        return  messageFetcher;
    }

    /**
     * 启动MessageFetcher线程
     * @param target
     */
    public void startConsume(MessageFetcher target){
        new Thread(target, "consumer[" + StrUtil.topicPartitionsStr(target.assignment()) + "] fetcher thread").start();
        log.info("start consumer[" + StrUtil.topicPartitionsStr(target.assignment()) + "] fetcher thread");
    }

    public void stopConsuerAsync(String appName){
        MessageFetcher messageFetcher = name2Fetcher.get(appName);
        if(messageFetcher != null){
            messageFetcher.close();
        }
        else{
            throw new IllegalStateException("manager does not have MessageFetcher named \"" + appName + "\"");
        }
    }

    public void stopConsumerSync(String appName){
        MessageFetcher messageFetcher = name2Fetcher.get(appName);
        if(messageFetcher != null){
            messageFetcher.close();
            while(!messageFetcher.isTerminated()){
                try {
                    Thread.sleep(2 * 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        else{
            throw new IllegalStateException("manager does not have MessageFetcher named \"" + appName + "\"");
        }
    }
}
