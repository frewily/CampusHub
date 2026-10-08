package io.github.frewily.campushub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.OrderStatusResponse;
import io.github.frewily.campushub.entity.*;
import io.github.frewily.campushub.exception.*;
import io.github.frewily.campushub.mapper.*;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class OrderLifecycleService {
    private final VoucherOrderMapper orders;
    private final VoucherMapper vouchers;
    private final OrderCancellationMapper cancellations;
    private final AccountAccessMapper accounts;
    private final ResourceAuthorizationService authorization;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public OrderLifecycleService(VoucherOrderMapper orders, VoucherMapper vouchers,
            OrderCancellationMapper cancellations, AccountAccessMapper accounts,
            ResourceAuthorizationService authorization, TransactionTemplate transactions,
            StringRedisTemplate redis, ObjectMapper json) {
        this.orders = orders;
        this.vouchers = vouchers;
        this.cancellations = cancellations;
        this.accounts = accounts;
        this.authorization = authorization;
        this.transactions = transactions;
        this.redis = redis;
        this.json = json;
    }

    public OrderStatusResponse queryMine(Long id, Long voucherId) {
        long userId = requireUser(id, voucherId);
        try {
            VoucherOrder order = orders.findOwned(id, userId, voucherId);
            if (order != null) return persisted(order);
            if (!hasOwnedAcceptance(id, voucherId, userId)) throw new BusinessException(ErrorCode.NOT_FOUND);
            return new OrderStatusResponse(id.toString(), voucherId.toString(),
                    failedAcceptance(id, voucherId, userId) ? "REQUIRES_REVIEW" : "ACCEPTED", null, null, null);
        } catch (DataAccessException error) { throw unavailable(); }
    }

    public OrderStatusResponse cancelMine(Long id, Long voucherId) {
        long userId = requireUser(id, voucherId);
        try {
            return transactions.execute(status -> {
                VoucherOrder order = orders.lockOwned(id, userId, voucherId);
                if (order == null) {
                    if (hasOwnedAcceptance(id, voucherId, userId)) {
                        throw new BusinessException(ErrorCode.CONFLICT, "订单尚未落库，暂不能取消");
                    }
                    throw new BusinessException(ErrorCode.NOT_FOUND);
                }
                if (Integer.valueOf(4).equals(order.getStatus())) return persisted(order);
                if (!Integer.valueOf(1).equals(order.getStatus())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "只支持取消未支付订单，不提供退款");
                }
                Voucher voucher = vouchers.findFlashSale(voucherId);
                if (voucher == null || !Integer.valueOf(1).equals(voucher.getType())) throw unavailable();
                long expiresAt = new FlashSaleRules(voucher).getExpiresAtMillis();
                if (orders.cancelUnpaid(id, userId, voucherId) != 1 || orders.returnStock(voucherId) != 1) {
                    throw unavailable();
                }
                if (cancellations.insert(new OrderCancellation().setOrderId(id).setUserId(userId)
                        .setVoucherId(voucherId).setExpiresAtMs(expiresAt)) != 1) throw unavailable();
                return persisted(orders.findOwned(id, userId, voucherId));
            });
        } catch (DataAccessException error) { throw unavailable(); }
        // No Redis write in the transaction. HTTP cancellation success is not compensation completion.
    }

    private long requireUser(Long id, Long voucherId) {
        if (id == null || id <= 0 || voucherId == null || voucherId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        try {
            if (!authorization.canParticipateAsUser() || !"ACTIVE".equals(accounts.findAccountStatus(user.getId()))) {
                throw new BusinessException(ErrorCode.AUTHORIZATION_FAILED);
            }
        } catch (DataAccessException error) { throw unavailable(); }
        return user.getId();
    }

    private boolean hasOwnedAcceptance(Long id, Long voucherId, long userId) {
        Object accepted = redis.opsForHash().get("seckill:request:" + voucherId, Long.toString(userId));
        return id.toString().equals(accepted);
    }

    private boolean failedAcceptance(Long id, Long voucherId, long userId) {
        Object indexed = redis.opsForHash().get("stream.orders.failures", "order:" + id);
        if (indexed == null) return false;
        if (!(indexed instanceof String)) throw unavailable();
        List<MapRecord<String, Object, Object>> entries = redis.opsForStream().range("stream.orders.dead",
                Range.closed((String) indexed, (String) indexed));
        if (entries == null || entries.size() != 1) throw unavailable();
        try {
            Object payload = entries.get(0).getValue().get("payload");
            if (!(payload instanceof String)) throw unavailable();
            List<?> fields = json.readValue((String) payload, List.class);
            if (fields.size() != 6) throw unavailable();
            Map<Object, Object> values = new HashMap<>();
            for (int i = 0; i < fields.size(); i += 2) {
                if (values.put(fields.get(i), fields.get(i + 1)) != null) throw unavailable();
            }
            VoucherOrder original = OrderStreamConsumer.decode(values);
            if (!id.equals(original.getId()) || !voucherId.equals(original.getVoucherId())
                    || userId != original.getUserId()) throw unavailable();
        } catch (BusinessException error) { throw error; }
        catch (Exception invalidPayload) { throw unavailable(); }
        return redis.opsForHash().get("stream.orders.redrives", indexed) == null;
    }

    private OrderStatusResponse persisted(VoucherOrder order) {
        if (order == null || order.getStatus() == null) throw unavailable();
        String[] states = {"", "PENDING_PAYMENT", "PAID", "REDEEMED", "CANCELLED", "REFUNDING", "REFUNDED"};
        int code = order.getStatus();
        if (code < 1 || code >= states.length) throw unavailable();
        String compensation = null;
        if (code == 4) {
            OrderCancellation entry = cancellations.find(order.getId());
            compensation = entry == null ? "UNTRACKED" : entry.getStatus();
            if (!Arrays.asList("UNTRACKED", "PENDING", "COMPLETED", "REQUIRES_REVIEW").contains(compensation)) {
                throw unavailable();
            }
        }
        return new OrderStatusResponse(order.getId().toString(), order.getVoucherId().toString(),
                states[code], compensation, order.getCreateTime(), order.getUpdateTime());
    }

    private BusinessException unavailable() { return new BusinessException(ErrorCode.ORDER_STATE_UNAVAILABLE); }
}
