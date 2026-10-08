package io.github.frewily.campushub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.frewily.campushub.entity.Voucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);

    @Select("SELECT v.*, s.stock, s.begin_time, s.end_time FROM tb_voucher v " +
            "LEFT JOIN tb_seckill_voucher s ON s.voucher_id = v.id WHERE v.id = #{voucherId}")
    Voucher findFlashSale(@Param("voucherId") Long voucherId);
}
