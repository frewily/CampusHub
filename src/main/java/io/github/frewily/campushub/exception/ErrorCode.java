package io.github.frewily.campushub.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    VALIDATION_FAILED("VALIDATION_FAILED", "请求参数校验失败", HttpStatus.BAD_REQUEST),
    AUTHENTICATION_FAILED("AUTHENTICATION_FAILED", "认证失败", HttpStatus.UNAUTHORIZED),
    AUTHORIZATION_FAILED("AUTHORIZATION_FAILED", "无权执行该操作", HttpStatus.FORBIDDEN),
    RATE_LIMITED("RATE_LIMITED", "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),
    NOT_FOUND("NOT_FOUND", "请求的资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT("CONFLICT", "请求与当前资源状态冲突", HttpStatus.CONFLICT),
    ACTIVITY_NOT_STARTED("ACTIVITY_NOT_STARTED", "活动尚未开始", HttpStatus.CONFLICT),
    ACTIVITY_ENDED("ACTIVITY_ENDED", "活动已结束", HttpStatus.CONFLICT),
    ACTIVITY_INACTIVE("ACTIVITY_INACTIVE", "活动未上架", HttpStatus.CONFLICT),
    SOLD_OUT("SOLD_OUT", "库存不足", HttpStatus.CONFLICT),
    ALREADY_PARTICIPATED("ALREADY_PARTICIPATED", "已有参与记录，请查询原订单", HttpStatus.CONFLICT),
    ACTIVITY_UNAVAILABLE("ACTIVITY_UNAVAILABLE", "活动暂不可受理，请稍后使用同一账号重试", HttpStatus.SERVICE_UNAVAILABLE),
    ORDER_STATE_UNAVAILABLE("ORDER_STATE_UNAVAILABLE", "订单状态暂时无法确认，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE),
    SHOP_STATE_UNAVAILABLE("SHOP_STATE_UNAVAILABLE", "门店状态暂时无法确认，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE),
    IMAGE_STORAGE_UNAVAILABLE("IMAGE_STORAGE_UNAVAILABLE", "图片存储暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE),
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
