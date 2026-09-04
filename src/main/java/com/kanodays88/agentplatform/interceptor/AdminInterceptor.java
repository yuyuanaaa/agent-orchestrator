package com.kanodays88.agentplatform.interceptor;

import com.kanodays88.agentplatform.common.ErrorCode;
import com.kanodays88.agentplatform.common.UserRole;
import com.kanodays88.agentplatform.content.BaseContent;
import com.kanodays88.agentplatform.entity.dto.UserLoginDTO;
import com.kanodays88.agentplatform.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员鉴权拦截器。
 * <p>
 * 作用于 {@code /ai/admin/**} 管理端接口，在登录校验（LoginInterceptor）之后执行，
 * 仅放行角色为 admin 的用户，否则抛出 403。登录态还原由 RefreshTokenInterceptor 负责，
 * 因此此处能直接读取 BaseContent 中的角色字段。
 */
@Component
@Slf4j
public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserLoginDTO user = BaseContent.getUser();
        if (user == null || !UserRole.ADMIN.equals(user.getRole())) {
            log.info("拦截非管理员访问管理端: uri={}, user={}", request.getRequestURI(),
                    user == null ? null : user.getUserName());
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可访问");
        }
        return true;
    }
}
