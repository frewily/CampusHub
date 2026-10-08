package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.dto.response.OrderAcceptanceResult;
import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.mapper.VoucherMapper;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.utils.RedisIdWorker;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FlashSaleAdmissionService {
    private static final DefaultRedisScript<List> ADMISSION_SCRIPT = new DefaultRedisScript<>();
    static {
        ADMISSION_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        ADMISSION_SCRIPT.setResultType(List.class);
    }
    private final VoucherMapper vouchers;
    private final AccountAccessMapper accounts;
    private final ResourceAuthorizationService authorization;
    private final RedisIdWorker idWorker;
    private final StringRedisTemplate redis;

    public FlashSaleAdmissionService(VoucherMapper vouchers, AccountAccessMapper accounts,
                                    ResourceAuthorizationService authorization,
                                    RedisIdWorker idWorker, StringRedisTemplate redis) {
        this.vouchers = vouchers;
        this.accounts = accounts;
        this.authorization = authorization;
        this.idWorker = idWorker;
        this.redis = redis;
    }

    public Result admit(Long voucherId) {
        if (voucherId == null || voucherId <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        if (!authorization.canParticipateAsUser()
                || !"ACTIVE".equals(accounts.findAccountStatus(user.getId()))) {
            throw new BusinessException(ErrorCode.AUTHORIZATION_FAILED);
        }
        Voucher voucher = vouchers.findFlashSale(voucherId);
        if (voucher == null || !Integer.valueOf(1).equals(voucher.getType())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "限量活动不存在");
        }
        FlashSaleRules rules = new FlashSaleRules(voucher);
        List<String> keys = new ArrayList<>(FlashSaleRules.activityKeys(voucherId));
        keys.add("stream.orders");
        try {
            Long orderId = idWorker.nextId("order");
            if (orderId == null || orderId <= 0) throw new BusinessException(ErrorCode.ACTIVITY_UNAVAILABLE);
            List<?> result = redis.execute(ADMISSION_SCRIPT, keys, user.getId().toString(),
                    orderId.toString(), "1", String.valueOf(voucher.getStatus()),
                    rules.getBeginMillis() + "", rules.getEndMillis() + "", voucherId.toString());
            return decode(result);
        } catch (DataAccessException e) {
            // A timeout may occur AFTER Lua accepted the request. Retry the same user/activity.
            throw new BusinessException(ErrorCode.ACTIVITY_UNAVAILABLE);
        }
    }

    private Result decode(List<?> result) {
        if (result == null || result.size() != 2 || !(result.get(0) instanceof Long)) {
            throw new BusinessException(ErrorCode.ACTIVITY_UNAVAILABLE);
        }
        long code = (Long) result.get(0);
        if (code == 0 || code == 9) {
            try {
                long id = Long.parseLong(String.valueOf(result.get(1)));
                if (id <= 0) throw new NumberFormatException();
                return new OrderAcceptanceResult(id, code == 9);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.ACTIVITY_UNAVAILABLE);
            }
        }
        if (code == 1) throw new BusinessException(ErrorCode.SOLD_OUT);
        if (code == 2) throw new BusinessException(ErrorCode.ALREADY_PARTICIPATED);
        if (code == 4) throw new BusinessException(ErrorCode.ACTIVITY_NOT_STARTED);
        if (code == 5) throw new BusinessException(ErrorCode.ACTIVITY_ENDED);
        if (code == 6) throw new BusinessException(ErrorCode.ACTIVITY_INACTIVE);
        if (code == 7) throw new BusinessException(ErrorCode.AUTHORIZATION_FAILED);
        throw new BusinessException(ErrorCode.ACTIVITY_UNAVAILABLE);
    }
}
