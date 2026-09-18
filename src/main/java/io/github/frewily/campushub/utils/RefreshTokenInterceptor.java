package io.github.frewily.campushub.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import io.github.frewily.campushub.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    /**
     * 预处理方法，在请求到达 Controller 之前执行
     * @param request HTTP 请求对象，代表当前这次 HTTP 请求
     *                - 作用域：单次请求，请求结束后销毁
     *                - 用途：获取请求参数、请求头、客户端信息等
     *                - 通过 request.getSession() 可以获取会话对象
     * @param response HTTP 响应对象，用于向客户端返回响应
     * @param handler 被调用的处理器（通常是 Controller 方法）
     * @return true: 放行请求，继续执行后续拦截器和 Controller
     *         false: 拦截请求，不再继续执行
     */

    private StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> usermap = stringRedisTemplate.opsForHash().entries(key);
        if (usermap.isEmpty()) {
            return true;
        }
        UserDTO user = BeanUtil.fillBeanWithMap(usermap, new UserDTO(), false);
        /**
         * 什么是 ThreadLocal？
         * 每个线程都有自己独立的存储空间
         * 不同线程之间互不干扰
         * 可以在同一个线程的任何地方存取数据
         */
        UserHolder.saveUser(user);
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
