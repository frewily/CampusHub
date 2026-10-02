package io.github.frewily.campushub.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import javax.validation.constraints.*;

/** Partial update. Ownership and derived statistics cannot be changed here. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShopUpdateRequest {
    @NotNull(message = "门店ID不能为空")
    @Positive
    private Long id;
    @Pattern(regexp = "(?s).*\\S.*", message = "门店名称不能为空白")
    @Size(max = 128)
    private String name;
    @Positive
    private Long typeId;
    @Pattern(regexp = "(?s).*\\S.*", message = "门店图片不能为空白")
    @Size(max = 1024)
    private String images;
    @Size(max = 128)
    private String area;
    @Pattern(regexp = "(?s).*\\S.*", message = "门店地址不能为空白")
    @Size(max = 255)
    private String address;
    // Match the legacy UNSIGNED coordinate columns until their schema migration.
    @DecimalMin("0")
    @DecimalMax("180")
    private Double x;
    @DecimalMin("0")
    @DecimalMax("90")
    private Double y;
    @PositiveOrZero
    private Long avgPrice;
    @Size(max = 32)
    private String openHours;
}
