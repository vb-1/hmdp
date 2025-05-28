package com.hmdp.utils;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);

    // 不再需要构造函数注入 StringRedisTemplate
    // public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
    // }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 1. 判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            // 没有，需要拦截，设置状态码
            log.debug("LoginInterceptor: UserHolder is null. Path: {}. Intercepting request.", request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value()); // 401
            // 拦截
            return false;
        }
        // 有用户，则放行
        log.debug("LoginInterceptor: UserHolder has user: {}. Path: {}. Allowing request.", UserHolder.getUser().getId(), request.getRequestURI());
        return true;
    }

    // afterCompletion 中 UserHolder.removeUser() 由 RefreshTokenInterceptor 统一处理
}