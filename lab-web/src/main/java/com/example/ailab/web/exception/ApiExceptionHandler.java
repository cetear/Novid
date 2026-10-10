package com.example.ailab.web.exception;

import com.example.ailab.contract.error.LabException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.*;

/**
 * 对外使用稳定错误码，不回传异常栈、SQL、供应商响应或凭证。
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record Error(String code, String message, boolean retryable) {
    }

    /**
     * 业务失败按稳定类别映射 HTTP。
     */
    @ExceptionHandler(LabException.class)
    public ResponseEntity<Error> business(LabException e) {
        LOG.warn("event=api.rejected code={}", com.example.ailab.contract.error.DiagnosticFailure.code(e));
        int status = switch (e.code()) {
            case "AUTH_REQUIRED" -> 401;
            case "ACCESS_DENIED", "PASSWORD_CHANGE_REQUIRED" -> 403;
            case "APPROVAL_CONFLICT", "OPERATION_CONFLICT", "STALE_EXECUTION", "INDEX_NOT_READY", "SESSION_CONFLICT", "CONTEXT_VERSION_CONFLICT", "INGESTION_PLAN_CONFLICT", "INGESTION_RECOVERY_CONFLICT", "EMBEDDING_RESULT_UNKNOWN", "INGESTION_BUDGET_EXCEEDED" ->
                    409;
            case "APPROVAL_EXPIRED", "WORKFLOW_RETIRED" -> 410;
            case "FEE_CONFLICT", "FEE_SCOPE_REQUIRED", "FEE_PRICE_UNAVAILABLE", "BUDGET_EXCEEDED" -> 409;
            case "WORKFLOW_RESULT_UNAVAILABLE", "WORKFLOW_STATE_CONFLICT", "WORKFLOW_EXECUTOR_UNAVAILABLE", "WORKFLOW_REVIEW_REJECTED" -> 409;
            case "WORKFLOW_BINDING_MISSING", "WORKFLOW_ARCHITECTURE_MISMATCH", "WORKFLOW_GRAPH_INVALID" -> 409;
            case "INTERNAL_ERROR", "WORKFLOW_EXECUTION_FAILED" -> 500;
            case "WORKFLOW_PLAN_BUDGET_EXCEEDED", "WORKFLOW_REQUIREMENTS_UNSATISFIED", "WORKFLOW_UNIT_TOO_LARGE", "WORKFLOW_INDEX_CAPACITY_EXCEEDED", "WORKFLOW_EXTRACTION_CAPACITY_EXCEEDED" -> 409;
            case "PPT_EXPORT_EXHAUSTED", "PPT_IMAGE_MISSING", "PPT_IMAGE_SOURCE_REQUIRED", "PPT_TEXT_OVERFLOW", "PPT_PAGE_LIMIT", "PPT_STRUCTURE_INVALID", "PPT_IMAGE_INVALID" ->
                    409;
            case "PPT_FONT_UNAVAILABLE", "PPT_FONT_GLYPH_MISSING", "PPT_EXPORT_FAILED" -> 503;
            case "PPT_EXPORT_TIMEOUT" -> 504;
            case "FEE_LEDGER_UNAVAILABLE", "ACCESS_AUDIT_UNAVAILABLE" -> 503;
            case "RATE_LIMITED", "MODEL_RATE_LIMITED" -> 429;
            case "MODEL_CONFIGURATION_ERROR" -> 503;
            case "MODEL_TIMEOUT" -> 504;
            case "MODEL_UNAVAILABLE", "SEARCH_UNAVAILABLE", "NO_COMPATIBLE_FALLBACK", "MEDIA_CAPABILITY_UNAVAILABLE" ->
                    503;
            default -> 400;
        };
        // 配置错误需要运维修正；不能以503类别授权前端重复付费生成。
        return ResponseEntity.status(status).body(new Error(e.code(), e.getMessage(), !e.code().startsWith("PPT_") && !java.util.Set.of("MODEL_CONFIGURATION_ERROR", "FEE_LEDGER_UNAVAILABLE", "ACCESS_AUDIT_UNAVAILABLE").contains(e.code()) && (status == 429 || status == 503 || status == 504)));
    }

    /**
     * Bean Validation 与未知身份字段均拒绝请求。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, org.springframework.web.multipart.support.MissingServletRequestPartException.class, org.springframework.web.bind.MissingRequestHeaderException.class, org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<Error> arguments(Exception e) {
        LOG.warn("event=api.rejected code=INVALID_ARGUMENTS type={}", e.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(new Error("INVALID_ARGUMENTS", "请求格式或参数不合法", false));
    }

    /**
     * 上传超限不进入业务事务。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Error> upload(Exception e) {
        LOG.warn("event=api.rejected code=DOCUMENT_LIMIT_EXCEEDED");
        return ResponseEntity.status(413).body(new Error("DOCUMENT_LIMIT_EXCEEDED", "文件超过 10 MB 限额", false));
    }

    /**
     * 真实断线后响应已不可写；取消由 SSE 回调完成，此处不能再尝试输出 JSON 错误。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void disconnected(AsyncRequestNotUsableException failure) {
        LOG.warn("event=api.disconnected");
        // 只处理容器明确标记不可用的异步响应，普通业务与基础设施异常仍按原错误路径上报。
    }

    /**
     * 未分类异常不暴露内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> unexpected(Exception e) {
        LOG.error("event=api.failed code=INTERNAL_ERROR", com.example.ailab.contract.error.DiagnosticFailure.sanitized(e));
        return ResponseEntity.status(500).body(new Error("INTERNAL_ERROR", "服务暂不可用", false));
    }
}
