package com.kanodays88.skytakeoutai.exception;

import com.kanodays88.skytakeoutai.common.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：由业务代码主动抛出，携带明确的错误码与面向用户的提示信息。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_ERROR, message);
    }
}
