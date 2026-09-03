package com.kanodays88.skytakeoutai.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求参数 / 登录态载体。
 * <p>
 * 该对象会被序列化进 Redis（login:user:{token}）并在请求线程间传递（见 BaseContent），
 * 因此仅保留必要的身份信息，密码不会进入登录态。
 */
@Data
public class UserLoginDTO {

    /** 用户主键，登录成功后回填，用于订单等资源的归属校验 */
    private Long id;

    /** 用户名（登录账号） */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名长度不能超过 32 个字符")
    private String userName;

    /** 手机号，下单时作为默认联系电话 */
    private String phone;

    /** 密码（登录凭证） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需要在 6 到 64 个字符之间")
    private String password;
}
