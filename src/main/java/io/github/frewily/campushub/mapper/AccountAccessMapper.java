package io.github.frewily.campushub.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AccountAccessMapper {

    @Select("SELECT status FROM tb_user WHERE id = #{userId}")
    String findAccountStatus(@Param("userId") Long userId);

    @Select("SELECT role FROM tb_user_role WHERE user_id = #{userId}")
    List<String> findRoles(@Param("userId") Long userId);

    @Select("SELECT merchant_id FROM tb_shop WHERE id = #{shopId}")
    Long findMerchantIdByShopId(@Param("shopId") Long shopId);

    @Select("SELECT COUNT(*) FROM tb_merchant_member " +
            "WHERE user_id = #{userId} AND merchant_id = #{merchantId} AND status = 'ACTIVE'")
    int countActiveMerchantMembership(@Param("userId") Long userId,
                                      @Param("merchantId") Long merchantId);
}
