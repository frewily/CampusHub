package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.entity.ShopCacheInvalidation;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ShopCacheInvalidationMapper {
    @Insert("INSERT INTO tb_shop_cache_invalidation(shop_id,generation,create_time,update_time) " +
            "VALUES(#{id},#{generation},UTC_TIMESTAMP(),UTC_TIMESTAMP()) " +
            "ON DUPLICATE KEY UPDATE generation=#{generation},attempts=0,last_error=NULL,update_time=UTC_TIMESTAMP()")
    int enqueue(@Param("id") Long id, @Param("generation") String generation);

    @Select("SELECT * FROM tb_shop_cache_invalidation ORDER BY update_time,shop_id LIMIT 32")
    List<ShopCacheInvalidation> pending();

    @Select("SELECT * FROM tb_shop_cache_invalidation WHERE shop_id=#{id}")
    ShopCacheInvalidation find(@Param("id") Long id);

    @Delete("DELETE FROM tb_shop_cache_invalidation WHERE shop_id=#{id} AND generation=#{generation}")
    int complete(@Param("id") Long id, @Param("generation") String generation);

    @Update("UPDATE tb_shop_cache_invalidation SET attempts=LEAST(attempts+1,1000000)," +
            "last_error=#{reason},update_time=UTC_TIMESTAMP() WHERE shop_id=#{id} AND generation=#{generation}")
    int failed(@Param("id") Long id, @Param("generation") String generation, @Param("reason") String reason);
}
