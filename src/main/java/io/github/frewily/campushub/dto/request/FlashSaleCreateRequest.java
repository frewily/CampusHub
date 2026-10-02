package io.github.frewily.campushub.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlashSaleCreateRequest extends VoucherCreateRequest {
    @NotNull(message = "活动库存不能为空")
    @Positive(message = "活动库存必须为正数")
    private Integer stock;
    @NotNull(message = "活动开始时间不能为空")
    private LocalDateTime beginTime;
    @NotNull(message = "活动结束时间不能为空")
    private LocalDateTime endTime;

    @JsonIgnore
    @AssertTrue(message = "活动结束时间必须晚于开始时间")
    public boolean isTimeRangeValid() {
        return beginTime == null || endTime == null || endTime.isAfter(beginTime);
    }
}
