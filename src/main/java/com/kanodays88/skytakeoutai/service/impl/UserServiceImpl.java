package com.kanodays88.skytakeoutai.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kanodays88.skytakeoutai.common.ErrorCode;
import com.kanodays88.skytakeoutai.entity.User;
import com.kanodays88.skytakeoutai.entity.dto.UserLoginDTO;
import com.kanodays88.skytakeoutai.entity.vo.UserLoginVO;
import com.kanodays88.skytakeoutai.exception.BusinessException;
import com.kanodays88.skytakeoutai.service.UserService;
import com.kanodays88.skytakeoutai.mapper.UserMapper;
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
        return userLoginVO;
    }
}
