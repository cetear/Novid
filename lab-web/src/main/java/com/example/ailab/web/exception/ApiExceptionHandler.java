package com.example.ailab.web.exception;
import com.example.ailab.contract.error.LabException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.*;
/** 对外使用稳定错误码，不回传异常栈、SQL、供应商响应或凭证。 */
@RestControllerAdvice
public class ApiExceptionHandler {
    public record Error(String code,String message,boolean retryable){}
    /** 业务失败按稳定类别映射 HTTP。 */
    @ExceptionHandler(LabException.class)
    public ResponseEntity<Error> business(LabException e){
        int status=switch(e.code()){
            case "AUTH_REQUIRED"->401;case "ACCESS_DENIED","PASSWORD_CHANGE_REQUIRED"->403;
            case "APPROVAL_CONFLICT","OPERATION_CONFLICT","STALE_EXECUTION","INDEX_NOT_READY"->409;
            case "APPROVAL_EXPIRED"->410;case "RATE_LIMITED"->429;
            case "MODEL_TIMEOUT"->504;case "MODEL_UNAVAILABLE","SEARCH_UNAVAILABLE","NO_COMPATIBLE_FALLBACK","MEDIA_CAPABILITY_UNAVAILABLE"->503;
            default->400;
        };return ResponseEntity.status(status).body(new Error(e.code(),e.getMessage(),status==429||status==503||status==504));
    }
    /** Bean Validation 与未知身份字段均拒绝请求。 */
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class,org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<Error> arguments(Exception e){return ResponseEntity.badRequest().body(new Error("INVALID_ARGUMENTS","请求格式或参数不合法",false));}
    /** 上传超限不进入业务事务。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Error> upload(Exception e){return ResponseEntity.status(413).body(new Error("DOCUMENT_LIMIT_EXCEEDED","文件超过 10 MB 限额",false));}
    /** 未分类异常不暴露内部细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> unexpected(Exception e){return ResponseEntity.status(500).body(new Error("INTERNAL_ERROR","服务暂不可用",false));}
}
