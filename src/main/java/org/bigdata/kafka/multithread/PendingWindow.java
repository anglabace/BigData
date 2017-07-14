package org.bigdata.kafka.multithread;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Comparator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Created by hjq on 2017/7/4.
 */
public class PendingWindow {
    private static Logger log = LoggerFactory.getLogger(PendingWindow.class);
    private Map<TopicPartitionWithTime, OffsetAndMetadata> pendingOffsets;
    private PriorityBlockingQueue<ConsumerRecordInfo> queue;
    private final int slidingWindow;

    //标识是否有处理线程正在判断窗口满足
    private AtomicBoolean isChecking = new AtomicBoolean(false);

    public PendingWindow(int slidingWindow, Map<TopicPartitionWithTime, OffsetAndMetadata> pendingOffsets) {
        log.info("init pendingWindow, slidingWindow size = " + slidingWindow);
        this.slidingWindow = slidingWindow;
        this.pendingOffsets = pendingOffsets;
        this.queue = new PriorityBlockingQueue<>(slidingWindow, new Comparator<ConsumerRecordInfo>(){

            @Override
            public int compare(ConsumerRecordInfo o1, ConsumerRecordInfo o2) {
                if(o2 == null){
                    return 1;
                }

                long offset1 = o1.record().offset();
                long offset2 = o2.record().offset();

                if(offset1 > offset2){
                    return 1;
                }
                else if(offset1 == offset2){
                    return 0;
                }
                else {
                    return -1;
                }
            }
        });
    }

    public synchronized void clean(){
        log.info("queue clean up");
        queue.clear();
    }

    public void commitFinished(ConsumerRecordInfo record){
        log.debug("consumer record " + record.record() + " finished");
        queue.put(record);

        //保证同一时间只有一条处理线程判断窗口满足
        //多线判断窗口满足有点难,需要加锁,感觉性能还不如控制一次只有一条线程判断窗口满足,无锁操作queue,其余处理线程直接进队
        //原子操作设置标识
        if(queue.size() < slidingWindow){
            //队列大小满足窗口大小才去判断
            if(isChecking.compareAndSet(false, true)){
                //判断是否满足窗口,若满足,则提交Offset
                commitLatest(true);
                isChecking.set(false);
            }
        }
    }

    public void commitLatest(boolean isInWindow){
        //队列为空,则返回
        //窗口大小都没满足,则返回
        if(queue.size() <= 0 || (isInWindow && queue.size() < slidingWindow)){
            return;
        }

        log.info("commit largest continue finished consumer records offsets");

        //获取queue的视图大小
        //默认是整个queue的视图
        int viewSize = queue.size();
        if(isInWindow){
            //表示获取窗口视图(slidingWindow)
            viewSize = slidingWindow;
        }

        //复制视图
        ConsumerRecordInfo[] tmp =  new ConsumerRecordInfo[viewSize];
        queue.toArray(tmp);

        //获取基本信息
        long maxOffset = tmp[0].record().offset();
        String topic = tmp[0].record().topic();
        int partition = tmp[0].record().partition();
        if(isInWindow){
            //判断是否满足窗口
            long lastOffset = tmp[tmp.length - 1].record().offset();
            //因为Offset是连续的,如果刚好满足窗口,则视图的第一个Offset和最后一个Offset相减更好等于窗口大小-1
            if(lastOffset - maxOffset == (slidingWindow - 1)){
                //满足窗口
                maxOffset = lastOffset;

                //因为Offset是连续的,尽管queue不断插入,但是永远不会排序插入到前slidingWindow个中,所以可以直接poll掉前slidingWindow个
                for(int i = 0; i < slidingWindow; i++){
                    queue.poll();
                }
            }
            else{
                //不满足,则直接返回
                return;
            }
        }
        else{
            //需要提交处理完的连续的最新的Offset
            for(int i = 1; i < tmp.length; i++){
                long thisOffset = tmp[i].record().offset();
                //判断offset是否连续
                if(thisOffset - maxOffset == 1){
                    //连续
                    maxOffset = thisOffset;
                }
                else{
                    //非连续
                    //如果是获取queue最大Offset,就直接退出循环
                    break;
                }
            }
        }

        pendingToCommit(new TopicPartitionWithTime(new TopicPartition(topic, partition), System.currentTimeMillis()), new OffsetAndMetadata(maxOffset + 1));
    }

    private void pendingToCommit(TopicPartitionWithTime topicPartitionWithTime, OffsetAndMetadata offset){
        log.info("pending to commit offset = " + offset);
        this.pendingOffsets.put(topicPartitionWithTime, offset);
    }
}
