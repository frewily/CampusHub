package io.github.frewily.campushub.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import javax.validation.constraints.*;

/** Common voucher terms; the endpoint determines type and initial status. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VoucherCreateRequest {
    @NotNull(message = "门店ID不能为空")
    @Positive
    private Long shopId;
    @NotBlank(message = "优惠券标题不能为空")
    @Size(max = 255)
    private String title;
    @Size(max = 255)
    private String subTitle;
    @Size(max = 1024)
    private String rules;
    @NotNull(message = "支付金额不能为空")
    @PositiveOrZero
    private Long payValue;
    @NotNull(message = "抵扣金额不能为空")
    @Positive
    private Long actualValue;
}
