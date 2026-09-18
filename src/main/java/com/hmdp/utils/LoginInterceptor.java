package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

//登录拦截器,要实现HandlerInterceptor接口
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1 判断是否要拦截（ThreadLocal中是否有用户)
        if (UserHolder.getUser() == null){
            //没有，需要拦截，返回401
            response.setStatus(401);
            return false;
        }
        //2 用户已登录，返回true，放行
        return true;
    }
}
