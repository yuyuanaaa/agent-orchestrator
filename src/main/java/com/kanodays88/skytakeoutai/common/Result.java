package com.kanodays88.skytakeoutai.common;

import java.io.Serializable;

/**
 * 统一接口响应体。
 *
 * @param code    业务状态码，见 {@link ErrorCode}
 * @param message 提示信息，可直接展示给用户
 * @param data    业务数据
 */
public record Result<T>(int code, String message, T data) implements Serializable {

    public static <T> Result<T> success() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }
}
