package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.entity.OrderCancellation;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface OrderCancellationMapper {
    @Insert("INSERT INTO tb_order_cancellation(order_id,user_id,voucher_id,expires_at_ms,next_attempt_at,create_time,update_time) " +
            "VALUES(#{orderId},#{userId},#{voucherId},#{expiresAtMs},UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())")
    int insert(OrderCancellation cancellation);

    @Select("SELECT * FROM tb_order_cancellation WHERE order_id=#{orderId}")
    OrderCancellation find(@Param("orderId") Long orderId);

    @Select("SELECT * FROM tb_order_cancellation WHERE status='PENDING' " +
            "AND next_attempt_at <= UTC_TIMESTAMP() AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP()) " +
            "ORDER BY next_attempt_at,order_id LIMIT 32")
    List<OrderCancellation> due();

    @Update("UPDATE tb_order_cancellation SET lease_token=#{token},lease_until=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 30 SECOND)," +
            "attempts=attempts+1 WHERE order_id=#{id} AND status='PENDING' AND attempts<10 " +
            "AND next_attempt_at<=UTC_TIMESTAMP() AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP())")
    int claim(@Param("id") Long id, @Param("token") String token);

    @Update("UPDATE tb_order_cancellation SET status='COMPLETED',redis_result=#{result},last_error=NULL," +
            "completed_at=UTC_TIMESTAMP(),lease_token=NULL,lease_until=NULL " +
            "WHERE order_id=#{id} AND status='PENDING' AND lease_token=#{token}")
    int complete(@Param("id") Long id, @Param("token") String token, @Param("result") String result);

    @Update("UPDATE tb_order_cancellation SET status=IF(attempts>=10,'REQUIRES_REVIEW','PENDING')," +
            "last_error=#{reason},next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 SECOND)," +
            "lease_token=NULL,lease_until=NULL WHERE order_id=#{id} AND status='PENDING' AND lease_token=#{token}")
    int retry(@Param("id") Long id, @Param("token") String token, @Param("reason") String reason);

    @Update("UPDATE tb_order_cancellation SET status='REQUIRES_REVIEW',last_error=#{reason}," +
            "lease_token=NULL,lease_until=NULL WHERE order_id=#{id} AND status='PENDING' AND lease_token=#{token}")
    int review(@Param("id") Long id, @Param("token") String token, @Param("reason") String reason);

    @Update("UPDATE tb_order_cancellation SET status='REQUIRES_REVIEW',last_error='RetryBudgetExhausted'," +
            "lease_token=NULL,lease_until=NULL WHERE order_id=#{id} AND status='PENDING' AND attempts>=10 " +
            "AND (lease_until IS NULL OR lease_until<=UTC_TIMESTAMP())")
    int exhaust(@Param("id") Long id);
}
