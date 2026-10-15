package io.github.frewily.campushub.mapper;

import io.github.frewily.campushub.entity.BlogPublicationRequest;
import org.apache.ibatis.annotations.*;

public interface BlogPublicationRequestMapper {
    @Insert("INSERT INTO tb_blog_publication_request(user_id,request_key,request_hash,create_time) "
            + "VALUES(#{userId},#{requestKey},#{requestHash},UTC_TIMESTAMP())")
    @Options(timeout = 5)
    int reserve(@Param("userId") Long userId, @Param("requestKey") String requestKey,
                @Param("requestHash") String requestHash);

    // Shared current read: bypass an old RR snapshot without upgrading duplicate INSERT shared locks.
    @Select("SELECT user_id,request_key,request_hash,blog_id FROM tb_blog_publication_request "
            + "WHERE user_id=#{userId} AND request_key=#{requestKey} FOR SHARE")
    @Options(timeout = 5, useCache = false)
    BlogPublicationRequest findForReplay(@Param("userId") Long userId, @Param("requestKey") String requestKey);

    @Update("UPDATE tb_blog_publication_request SET blog_id=#{blogId} "
            + "WHERE user_id=#{userId} AND request_key=#{requestKey} AND request_hash=#{requestHash} AND blog_id IS NULL")
    @Options(timeout = 5)
    int complete(@Param("userId") Long userId, @Param("requestKey") String requestKey,
                 @Param("requestHash") String requestHash, @Param("blogId") Long blogId);
}
