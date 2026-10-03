package com.example.ailab.contract.error;

/**
 * 跨模块业务失败；不会携带数据库异常或供应商凭证。
 */
public final class LabException extends RuntimeException {
    private final String code;

    /**
     * 用稳定错误码表达失败，HTTP 状态由接入层映射。
     */
    public LabException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 返回供客户端判断的稳定错误码。
     */
    public String code() {
        return code;
    }

    /**
     * 创建统一拒绝异常，避免泄露他人资源是否存在。
     */
    public static LabException denied() {
        return new LabException("ACCESS_DENIED", "无权访问该资源");
    }

    /**
     * 创建非法参数异常。
     */
    public static LabException invalid(String message) {
        return new LabException("INVALID_ARGUMENTS", message);
    }
}

