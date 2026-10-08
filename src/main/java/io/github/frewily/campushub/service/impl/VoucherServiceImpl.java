package io.github.frewily.campushub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.Voucher;
import io.github.frewily.campushub.mapper.VoucherMapper;
import io.github.frewily.campushub.entity.SeckillVoucher;
import io.github.frewily.campushub.service.ISeckillVoucherService;
import io.github.frewily.campushub.service.IVoucherService;
import io.github.frewily.campushub.service.FlashSaleRedisPublisher;
import io.github.frewily.campushub.service.FlashSaleRules;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

import javax.annotation.Resource;
import java.util.List;


@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private FlashSaleRedisPublisher flashSaleRedisPublisher;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    @PreAuthorize("@resourceAuthorization.canManagePromotion(#voucher.shopId)")
    public Result addVoucher(Voucher voucher) {
        save(voucher);
        return Result.ok(voucher.getId());
    }

    @Override
    @Transactional
    @PreAuthorize("@resourceAuthorization.canManagePromotion(#voucher.shopId)")
    public void addSeckillVoucher(Voucher voucher) {
        new FlashSaleRules(voucher);
        // Existing TIMESTAMP columns store whole seconds; normalize both projections first.
        voucher.setBeginTime(voucher.getBeginTime().withNano(0));
        voucher.setEndTime(voucher.getEndTime().withNano(0));
        if (!save(voucher)) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "活动写入失败");
        }
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "活动库存写入失败");
        }
        flashSaleRedisPublisher.publishAfterCommit(voucher);
    }
}
