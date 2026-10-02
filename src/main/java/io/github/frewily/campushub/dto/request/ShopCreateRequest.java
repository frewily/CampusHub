package io.github.frewily.campushub.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import javax.validation.constraints.*;

/** Writable shop fields. Counters, IDs and timestamps are server-managed. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShopCreateRequest {
    @NotBlank(message = "门店名称不能为空")
    @Size(max = 128)
    private String name;
    @NotNull(message = "门店类型不能为空")
    @Positive
    private Long typeId;
    @Positive
    private Long merchantId;
    @NotBlank(message = "门店图片不能为空")
    @Size(max = 1024)
    private String images;
    @Size(max = 128)
    private String area;
    @NotBlank(message = "门店地址不能为空")
    @Size(max = 255)
    private String address;
    @NotNull(message = "经度不能为空")
    // The legacy tb_shop coordinate columns are UNSIGNED.
    @DecimalMin("0")
    @DecimalMax("180")
    private Double x;
    @NotNull(message = "纬度不能为空")
    @DecimalMin("0")
    @DecimalMax("90")
    private Double y;
    @PositiveOrZero
    private Long avgPrice;
    @Size(max = 32)
    private String openHours;
}
