package io.github.frewily.campushub.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    VALIDATION_FAILED("VALIDATION_FAILED", "请求参数校验失败", HttpStatus.BAD_REQUEST),
    AUTHENTICATION_FAILED("AUTHENTICATION_FAILED", "认证失败", HttpStatus.UNAUTHORIZED),
    RATE_LIMITED("RATE_LIMITED", "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),
    NOT_FOUND("NOT_FOUND", "请求的资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT("CONFLICT", "请求与当前资源状态冲突", HttpStatus.CONFLICT),
    NOT_IMPLEMENTED("NOT_IMPLEMENTED", "功能尚未实现", HttpStatus.NOT_IMPLEMENTED),
    OPERATION_FAILED("OPERATION_FAILED", "业务操作失败", HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务器异常", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }
}
