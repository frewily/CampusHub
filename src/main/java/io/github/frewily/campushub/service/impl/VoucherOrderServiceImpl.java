package io.github.frewily.campushub.service.impl;

import cn.hutool.core.bean.BeanUtil;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.VoucherOrder;
import io.github.frewily.campushub.mapper.VoucherOrderMapper;
import io.github.frewily.campushub.service.ISeckillVoucherService;
import io.github.frewily.campushub.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.frewily.campushub.utils.RedisIdWorker;
import io.github.frewily.campushub.utils.UserHolder;
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

    private final ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean isRunning = true;

    @PostConstruct
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
        isRunning = false;
        seckillOrderExecutor.shutdown();
        try {
            if (!seckillOrderExecutor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                seckillOrderExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            seckillOrderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_STREAM_GROUP, ORDER_STREAM_CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
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
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_STREAM_GROUP, ORDER_STREAM_CONSUMER),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
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
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        RLock lock = redissonClient.getLock("lock:order:" + voucherOrder.getUserId());
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 锁竞争属于可重试失败，抛出异常让 Stream 消息保留在 pending list
            throw new IllegalStateException("订单处理锁竞争，稍后重试");
        }
        try {
            createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)//全部要转成字符串
        );
        int r = result.intValue();//转成int类型
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        return Result.ok(orderId);
    }
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        transactionTemplate.executeWithoutResult(status -> persistVoucherOrder(voucherOrder));
    }

    private void persistVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            // 重复消费命中数据库幂等保护，视为订单已成功落库
            log.info("订单已存在，跳过重复持久化，userId={}, voucherId={}", userId, voucherOrder.getVoucherId());
            return;
        }
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            throw new IllegalStateException("数据库库存扣减失败，订单稍后重试");
        }
        if (!save(voucherOrder)) {
            throw new IllegalStateException("订单写入失败，事务将回滚");
        }
    }
}
