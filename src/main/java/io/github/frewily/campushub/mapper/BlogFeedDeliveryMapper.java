package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.entity.BlogFeedDelivery;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface BlogFeedDeliveryMapper {
    @Insert("INSERT INTO tb_blog_feed_delivery(blog_id,recipient_user_id,score_millis,next_attempt_at,create_time,update_time) "
            + "VALUES(#{blogId},#{recipientUserId},#{scoreMillis},UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())")
    @Options(timeout = 5)
    int enqueue(@Param("blogId") Long blogId, @Param("recipientUserId") Long recipientUserId,
                @Param("scoreMillis") Long scoreMillis);

    @Select("SELECT blog_id,recipient_user_id,score_millis,attempts,last_error FROM tb_blog_feed_delivery "
            + "WHERE next_attempt_at <= UTC_TIMESTAMP() ORDER BY next_attempt_at,blog_id,recipient_user_id LIMIT 32")
    @Options(timeout = 5, useCache = false)
    List<BlogFeedDelivery> pending();

    @Delete("DELETE FROM tb_blog_feed_delivery WHERE blog_id=#{blogId} AND recipient_user_id=#{recipientUserId} "
            + "AND score_millis=#{scoreMillis}")
    @Options(timeout = 5)
    int complete(@Param("blogId") Long blogId, @Param("recipientUserId") Long recipientUserId,
                 @Param("scoreMillis") Long scoreMillis);

    @Update("UPDATE tb_blog_feed_delivery SET attempts=LEAST(attempts+1,1000000),last_error=#{reason},"
            + "next_attempt_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 SECOND),update_time=UTC_TIMESTAMP() "
            + "WHERE blog_id=#{blogId} AND recipient_user_id=#{recipientUserId} AND score_millis=#{scoreMillis}")
    @Options(timeout = 5)
    int failed(@Param("blogId") Long blogId, @Param("recipientUserId") Long recipientUserId,
               @Param("scoreMillis") Long scoreMillis, @Param("reason") String reason);
}
