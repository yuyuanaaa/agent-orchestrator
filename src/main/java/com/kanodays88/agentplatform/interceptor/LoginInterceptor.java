package com.kanodays88.agentplatform.interceptor;

import com.kanodays88.agentplatform.common.ErrorCode;
import com.kanodays88.agentplatform.content.BaseContent;
import com.kanodays88.agentplatform.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录校验拦截器。
 * <p>
 * 只做「是否登录」的判断，token 续期与登录态还原由 {@link RefreshTokenInterceptor} 负责，
 * 后者以更小的 order 值优先执行，因此长时间停留在免登录页面也不会掉线。
 * <p>
 * 未登录时抛出 {@link BusinessException}，由 GlobalExceptionHandler 统一输出 401 JSON 响应。
 */
@Component
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (BaseContent.getUser() == null) {
            log.info("拦截未登录请求: uri={}", request.getRequestURI());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return true;
    }
}
