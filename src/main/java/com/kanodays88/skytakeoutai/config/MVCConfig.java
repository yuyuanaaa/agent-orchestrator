package com.kanodays88.skytakeoutai.config;

import com.kanodays88.skytakeoutai.interceptor.LoginInterceptor;
import com.kanodays88.skytakeoutai.interceptor.RefreshTokenInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：拦截器注册与静态资源映射。
 */
@Configuration
@Slf4j
public class MVCConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Autowired
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("注册拦截器");
        // order 值越小优先级越高；刷新 token 的拦截器先执行，保证登录态在校验前已还原
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/ai/**")
                .excludePathPatterns("/ai/user/login")
                .order(1);
    }

    /**
     * 静态资源映射。
     * <p>
     * 注意：不要把整个 tmp 目录（含全部用户的会话记忆）映射到 /** 上，否则会泄露他人会话数据。
     * 这里只开放两类必要的只读目录。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 菜品/套餐图片
        registry.addResourceHandler("/upload/**")
                .addResourceLocations("classpath:/static/upload/");
        // 智能体在会话中产出的文件（PDF、下载的资源等）
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:./tmp/");
    }
}
