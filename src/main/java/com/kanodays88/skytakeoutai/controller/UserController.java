package com.kanodays88.skytakeoutai.controller;

import com.kanodays88.skytakeoutai.common.Result;
import com.kanodays88.skytakeoutai.entity.dto.UserLoginDTO;
import com.kanodays88.skytakeoutai.entity.vo.UserLoginVO;
import com.kanodays88.skytakeoutai.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai/user")
@CrossOrigin
@Slf4j
public class UserController {

    @Autowired
    private UserService userServiceImpl;

    /**
     * 登录（用户名不存在时自动注册）
     */
    @PostMapping("/login")
    public Result<UserLoginVO> login(@Valid @RequestBody UserLoginDTO userLoginDTO) {
        return Result.success(userServiceImpl.login(userLoginDTO));
    }
}
