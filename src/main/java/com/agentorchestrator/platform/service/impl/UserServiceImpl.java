package com.agentorchestrator.platform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.agentorchestrator.platform.common.ErrorCode;
import com.agentorchestrator.platform.common.UserRole;
import com.agentorchestrator.platform.config.AdminProperties;
import com.agentorchestrator.platform.entity.User;
import com.agentorchestrator.platform.entity.dto.UserLoginDTO;
import com.agentorchestrator.platform.entity.vo.UserLoginVO;
import com.agentorchestrator.platform.exception.BusinessException;
import com.agentorchestrator.platform.service.UserService;
import com.agentorchestrator.platform.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现：注册即登录。
 * <p>
 * 用户名不存在时自动创建账号；已存在则校验 BCrypt 密码。
 * 登录成功后把用户身份写入 Redis（login:user:{token}），由 RefreshTokenInterceptor 在后续请求中续期并还原登录态。
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService {

    /** 登录态有效期，与 RefreshTokenInterceptor 中的续期时长保持一致 */
    private static final long LOGIN_TTL_MINUTES = 60;

    private static final String LOGIN_KEY_PREFIX = "login:user:";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AdminProperties adminProperties;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public UserLoginVO login(UserLoginDTO userLoginDTO) {
        List<User> existUsers = query().eq("name", userLoginDTO.getUserName()).list();
        User user;
        if (existUsers == null || existUsers.isEmpty()) {
            // 用户名未占用，直接注册
            user = new User();
            user.setName(userLoginDTO.getUserName());
            user.setPhone(userLoginDTO.getPhone());
            user.setPassword(passwordEncoder.encode(userLoginDTO.getPassword()));
            if (userMapper.insert(user) <= 0) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，请重试");
            }
        } else {
            // 用户已存在，校验密码
            User exist = existUsers.get(0);
            String storedPassword = exist.getPassword();
            if (storedPassword == null || storedPassword.isBlank()
                    || !passwordEncoder.matches(userLoginDTO.getPassword(), storedPassword)) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名已被占用或密码错误");
            }
            user = exist;
        }

        // 登录成功：把身份信息写入 Redis，key 使用随机 token
        String token = UUID.randomUUID().toString();
        UserLoginDTO loginState = new UserLoginDTO();
        loginState.setId(user.getId());
        loginState.setUserName(user.getName());
        loginState.setRole(resolveRole(user.getName()));
        loginState.setPhone(user.getPhone());

        // StringRedisTemplate 要求所有值都是字符串，这里统一转换
        Map<String, Object> userMap = BeanUtil.beanToMap(loginState, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> String.valueOf(fieldValue)));
        stringRedisTemplate.opsForHash().putAll(LOGIN_KEY_PREFIX + token, userMap);
        stringRedisTemplate.expire(LOGIN_KEY_PREFIX + token, LOGIN_TTL_MINUTES, TimeUnit.MINUTES);

        UserLoginVO userLoginVO = new UserLoginVO();
        userLoginVO.setToken(token);
        userLoginVO.setUserId(user.getId());
        userLoginVO.setUserName(user.getName());
        userLoginVO.setRole(resolveRole(user.getName()));
        return userLoginVO;
    }

    /**
     * 根据用户名白名单判定角色。
     * <p>
     * 角色不落库，由配置（app.admin.usernames）驱动，登录时实时判定，避免历史账号缺 role 字段。
     */
    private String resolveRole(String userName) {
        return adminProperties.isAdmin(userName) ? UserRole.ADMIN : UserRole.USER;
    }
}
