package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class FlashSaleRedisPublisher {
    private static final DefaultRedisScript<Long> INITIALIZE_SCRIPT = new DefaultRedisScript<>();
    static {
        INITIALIZE_SCRIPT.setLocation(new ClassPathResource("initialize-flash-sale.lua"));
        INITIALIZE_SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;

    public FlashSaleRedisPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publishAfterCommit(Voucher voucher) {
        FlashSaleRules rules = new FlashSaleRules(voucher);
        Long voucherId = voucher.getId();
        Integer stock = voucher.getStock();
        if (voucherId == null || stock == null || stock <= 0 || !Integer.valueOf(1).equals(voucher.getStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "活动初始化参数无效");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("活动发布必须在数据库事务中注册");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    Long result = redis.execute(INITIALIZE_SCRIPT, FlashSaleRules.activityKeys(voucherId),
                            stock.toString(), rules.getBeginMillis() + "", rules.getEndMillis() + "",
                            rules.getExpiresAtMillis() + "");
                    if (!Long.valueOf(0).equals(result)) unavailable();
                } catch (DataAccessException e) {
                    unavailable();
                }
            }

            private void unavailable() {
                log.error("活动数据库已提交，但 Redis 初始化未确认成功，voucherId={}", voucherId);
                throw new BusinessException(ErrorCode.ACTIVITY_UNAVAILABLE,
                        "活动已保存但 Redis 初始化未确认，请检查原活动，勿重复创建");
            }
        });
    }
}
