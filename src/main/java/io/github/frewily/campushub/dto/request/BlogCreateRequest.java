package io.github.frewily.campushub.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import javax.validation.constraints.*;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlogCreateRequest {
    @NotNull(message = "门店ID不能为空")
    @Positive
    private Long shopId;
    @NotBlank(message = "标题不能为空")
    @Size(max = 255)
    private String title;
    @NotBlank(message = "动态图片不能为空")
    @Size(max = 2048)
    private String images;
    @NotBlank(message = "动态内容不能为空")
    @Size(max = 2048)
    private String content;
}
