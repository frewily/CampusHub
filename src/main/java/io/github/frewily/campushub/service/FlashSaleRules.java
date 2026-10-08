package io.github.frewily.campushub.service;

import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.utils.RedisConstants;
import lombok.Getter;

import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/** Immutable projection of existing voucher/flash-sale tables. Times are UTC. */
@Getter
public final class FlashSaleRules {
    private final long beginMillis;
    private final long endMillis;
    private final long expiresAtMillis;

    public FlashSaleRules(Voucher voucher) {
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            throw new BusinessException(ErrorCode.ACTIVITY_UNAVAILABLE);
        }
        try {
            beginMillis = voucher.getBeginTime().withNano(0).toInstant(ZoneOffset.UTC).toEpochMilli();
            endMillis = voucher.getEndTime().withNano(0).toInstant(ZoneOffset.UTC).toEpochMilli();
            expiresAtMillis = Math.addExact(endMillis, RedisConstants.SECKILL_RETENTION_MILLIS);
        } catch (ArithmeticException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "活动时间超出支持范围");
        }
        // Match MySQL's existing TIMESTAMP columns (1970 through January 2038).
        if (beginMillis < 1000 || endMillis > 2147483647000L || endMillis <= beginMillis) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "活动时间超出支持范围或结束时间无效");
        }
    }

    public static List<String> activityKeys(Long voucherId) {
        return Arrays.asList(RedisConstants.SECKILL_STOCK_KEY + voucherId,
                RedisConstants.SECKILL_ORDER_KEY + voucherId,
                RedisConstants.SECKILL_ACTIVITY_KEY + voucherId,
                RedisConstants.SECKILL_REQUEST_KEY + voucherId);
    }
}
