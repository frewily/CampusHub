package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final String ORDER_STREAM_KEY = "stream.orders";
    private static final String ORDER_STREAM_GROUP = "g1";
    private static final String ORDER_STREAM_CONSUMER = "c1";

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Value("${campushub.order.stream-consumer-enabled:true}")
    private boolean streamConsumerEnabled;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建线程池
    private final ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor();

    // 用于控制线程停止的volatile标志
    private volatile boolean isRunning = true;

    private IVoucherOrderService proxy;

    @PostConstruct//表示该方法启动时执行
    private void init(){
        if (!streamConsumerEnabled) {
            log.info("订单 Stream 消费者已通过配置关闭");
            return;
        }
        ensureConsumerGroup();
        seckillOrderExecutor.submit(new VoucherOrderHandler());
    }

    private void ensureConsumerGroup() {
        byte[] streamKey = stringRedisTemplate.getStringSerializer().serialize(ORDER_STREAM_KEY);
        try {
            stringRedisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            streamKey,
                            ORDER_STREAM_GROUP,
                            ReadOffset.from("0-0"),
                            true
                    )
            );
            log.info("已创建订单 Stream 消费组，stream={}, group={}", ORDER_STREAM_KEY, ORDER_STREAM_GROUP);
        } catch (DataAccessException e) {
            if (containsBusyGroup(e)) {
                log.debug("订单 Stream 消费组已存在，stream={}, group={}", ORDER_STREAM_KEY, ORDER_STREAM_GROUP);
                return;
            }
            throw e;
        }
    }

    private boolean containsBusyGroup(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @PreDestroy
    public void destroy() {
        // 设置停止标志
        isRunning = false;
        // 关闭线程池
        seckillOrderExecutor.shutdown();
        try {
            // 等待60秒让线程正常结束
            if (!seckillOrderExecutor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                // 如果超时，强制关闭
                seckillOrderExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            seckillOrderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private class VoucherOrderHandler implements Runnable {
        /**
         * 线程任务
         */
        @Override
        public void run() {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    //1. 获取消息队列中的订单信息 XREADGOUP GROUP g1 c1 COUNT 1 BLOCK 2000 STEAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_STREAM_GROUP, ORDER_STREAM_CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    //2. 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        //2.1 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3 解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //3 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //4 ACK确认  SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM_KEY, ORDER_STREAM_GROUP, record.getId());
                } catch (Exception e) {
                    if (!isRunning || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    //1. 获取pending-list中的订单信息 XREADGOUP GROUP g1 c1 COUNT 1 STEAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_STREAM_GROUP, ORDER_STREAM_CONSUMER),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.from("0"))
                    );
                    //2. 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        //2.1 如果获取失败，说明penging-list没有异常消息，结束循环
                        break;
                    }
                    //3 解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //3 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //4 ACK确认  SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM_KEY, ORDER_STREAM_GROUP, record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    /*private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //1. 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }*/
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + voucherOrder.getUserId());
        //2.尝试获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 锁竞争属于可重试失败，抛出异常让 Stream 消息保留在 pending list
            throw new IllegalStateException("订单处理锁竞争，稍后重试");
        }
        try {
            //3.创建订单；持久化失败必须向上传播，外层不会 ACK
            createVoucherOrder(voucherOrder);
        } finally {
            //4.释放锁
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        Long orderId = redisIdWorker.nextId("order");
        //1 执行Lua脚本

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)//全部要转成字符串
        );
        //2 判断结果是否为0
        int r = result.intValue();//转成int类型
        if (r != 0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //3 返回订单id
        return Result.ok(orderId);
    }
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1 执行Lua脚本

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2 判断结果是否为0
        int r = result.intValue();//转成int类型
        if (r != 0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 为0，代表有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //2.3 订单ID
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.4 用户ID
        voucherOrder.setUserId(userId);
        //2.5  代金券ID
        voucherOrder.setVoucherId(voucherId);
        //2.6  放入阻塞队列
        orderTasks.add(voucherOrder);
        //3 返回订单id
        return Result.ok(orderId);
    }*/

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        transactionTemplate.executeWithoutResult(status -> persistVoucherOrder(voucherOrder));
    }

    private void persistVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户ID
        Long userId = voucherOrder.getUserId();
        //2.一人一单逻辑
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            // 重复消费命中数据库幂等保护，视为订单已成功落库
            log.info("订单已存在，跳过重复持久化，userId={}, voucherId={}", userId, voucherOrder.getVoucherId());
            return;
        }
        //3.扣减库存
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            throw new IllegalStateException("数据库库存扣减失败，订单稍后重试");
        }
        //4.创建订单
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单写入失败，事务将回滚");
        }
    }
}
