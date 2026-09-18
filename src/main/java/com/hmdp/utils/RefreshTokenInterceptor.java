package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

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

    //由于拦截器是手动添加的，不由srping管理，所以需要手动注入
    private StringRedisTemplate stringRedisTemplate;
    //构造器注入
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1 从请求头中获得token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        //2 基于token获取redis中的用户信息(.get只是获得单个key中的某个字段，.entries获得所有字段)
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> usermap = stringRedisTemplate.opsForHash().entries(key);
        //3 判断用户是否存在（是否已登录）
        if (usermap.isEmpty()) {
            return true;
        }
        //5 将查询到的数据转为 UserDTO 对象
        UserDTO user = BeanUtil.fillBeanWithMap(usermap, new UserDTO(), false);
        //6 用户存在，将用户信息保存到 ThreadLocal，方便在当前线程中随时获取
        /**
         * 什么是 ThreadLocal？
         * 每个线程都有自己独立的存储空间
         * 不同线程之间互不干扰
         * 可以在同一个线程的任何地方存取数据
         */
        UserHolder.saveUser(user);
        //7 刷新token有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //6 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
