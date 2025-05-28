package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate; // RefreshTokenInterceptor需要，通过@Resource注入
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource // 将由Spring容器管理的RefreshTokenInterceptor注入进来
    private RefreshTokenInterceptor refreshTokenInterceptor;

    // LoginInterceptor现在不依赖StringRedisTemplate，可以直接new
    // @Resource
    // private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 拦截器执行顺序由order值决定，值越小越先执行

        // 1. Token刷新及用户状态恢复拦截器
        registry.addInterceptor(refreshTokenInterceptor) // 使用注入的Bean
                .addPathPatterns("/**") // 拦截所有请求
                .order(0); // 最先执行，尝试恢复用户登录状态

        // 2. 登录校验拦截器
        registry.addInterceptor(new LoginInterceptor()) // LoginInterceptor不依赖Spring Bean，直接new
                .excludePathPatterns( // 配置不需要登录就能访问的路径
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login",
                        "/user/logout" // 登出接口也应该可以被未登录（或令牌失效）的用户访问（虽然逻辑上是已登录用户操作）
                        // 或者说，登出操作本身不需要严格的“已登录”检查，而是令牌清理操作。
                ).order(1); // 在RefreshTokenInterceptor之后执行，检查是否真的需要登录
    }
}