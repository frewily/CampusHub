package io.github.frewily.campushub.dto;

import io.github.frewily.campushub.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;
    private String errorCode;
    private String errorMsg;
    private Object data;
    private Long total;

    public static Result ok() {
        return new Result(true, null, null, null, null);
    }

    public static Result ok(Object data) {
        return new Result(true, null, null, data, null);
    }

    public static Result ok(List<?> data, Long total) {
        return new Result(true, null, null, data, total);
    }

    public static Result fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getDefaultMessage());
    }

    public static Result fail(ErrorCode errorCode, String errorMsg) {
        return new Result(false, errorCode.getCode(), errorMsg, null, null);
    }
}
