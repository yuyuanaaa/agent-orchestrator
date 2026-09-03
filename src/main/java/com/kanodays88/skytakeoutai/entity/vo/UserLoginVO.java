package com.kanodays88.skytakeoutai.entity.vo;

import lombok.Data;

/**
 * 登录结果。
 * <p>
 * 失败信息统一由 {@link com.kanodays88.skytakeoutai.common.Result} 承载，这里只返回成功后的数据。
 */
@Data
public class UserLoginVO {

    /** 登录令牌，后续请求通过 authorization 请求头携带 */
    private String token;

    /** 用户主键 */
    private Long userId;

    /** 用户名 */
    private String userName;
}
