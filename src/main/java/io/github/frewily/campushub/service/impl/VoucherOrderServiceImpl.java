package io.github.frewily.campushub.service.impl;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.VoucherOrder;
import io.github.frewily.campushub.exception.OrderProcessingException;
import io.github.frewily.campushub.mapper.VoucherOrderMapper;
import io.github.frewily.campushub.service.ISeckillVoucherService;
import io.github.frewily.campushub.service.IVoucherOrderService;
import io.github.frewily.campushub.service.FlashSaleAdmissionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;

import static io.github.frewily.campushub.exception.OrderProcessingException.Reason.*;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private FlashSaleAdmissionService flashSaleAdmissionService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        RLock lock = redissonClient.getLock("lock:order:" + voucherOrder.getUserId());
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 锁竞争属于可重试失败，抛出异常让 Stream 消息保留在 pending list
            throw new OrderProcessingException(LOCK_BUSY);
        }
        try {
            createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        return flashSaleAdmissionService.admit(voucherId);
    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        transactionTemplate.executeWithoutResult(status -> persistVoucherOrder(voucherOrder));
    }

    private void persistVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        VoucherOrder existing = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).one();
        if (existing != null) {
            if (!voucherOrder.getId().equals(existing.getId())) {
                throw new OrderProcessingException(ORDER_ID_CONFLICT);
            }
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
            throw new OrderProcessingException(DATABASE_STOCK_UNAVAILABLE);
        }
        if (!save(voucherOrder)) {
            throw new OrderProcessingException(ORDER_INSERT_FAILED);
        }
    }
}
