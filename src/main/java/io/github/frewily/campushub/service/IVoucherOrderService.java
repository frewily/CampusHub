package io.github.frewily.campushub.service;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀优惠券
     * @param voucherId 优惠券id
     * @return 订单id
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建优惠券订单（异步处理）
     * @param voucherOrder 订单对象
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
