package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    @Select("SELECT * FROM tb_voucher_order WHERE id=#{id} AND user_id=#{userId} AND voucher_id=#{voucherId}")
    VoucherOrder findOwned(@Param("id") Long id, @Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Select("SELECT * FROM tb_voucher_order WHERE id=#{id} AND user_id=#{userId} AND voucher_id=#{voucherId} FOR UPDATE")
    VoucherOrder lockOwned(@Param("id") Long id, @Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Update("UPDATE tb_voucher_order SET status=4,update_time=UTC_TIMESTAMP() " +
            "WHERE id=#{id} AND user_id=#{userId} AND voucher_id=#{voucherId} AND status=1")
    int cancelUnpaid(@Param("id") Long id, @Param("userId") Long userId, @Param("voucherId") Long voucherId);

    @Update("UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=#{voucherId} AND stock>=0 AND stock<2147483647")
    int returnStock(@Param("voucherId") Long voucherId);

}
