package com.agentorchestrator.platform.common;

/**
 * 统一错误码。
 * <p>
 * 2xx 段留给 HTTP 语义（200 成功），4xx 表示调用方问题，1xxx 表示业务规则拒绝，5xxx 表示系统异常。
 */
public enum ErrorCode {

    SUCCESS(200, "成功"),

    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "无权访问该资源"),
    NOT_FOUND(404, "资源不存在"),
    FILE_TOO_LARGE(413, "上传文件超出大小限制"),

    /** 业务规则拒绝，例如订单不存在、库存不足等 */
    BUSINESS_ERROR(1001, "业务处理失败"),

    /** 模型调用、工具执行等下游依赖异常 */
    AI_SERVICE_ERROR(1002, "AI 服务调用失败，请稍后重试"),

    SYSTEM_ERROR(5000, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
