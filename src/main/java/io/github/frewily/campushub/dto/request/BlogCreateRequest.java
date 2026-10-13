package io.github.frewily.campushub.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import javax.validation.constraints.*;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlogCreateRequest {
    // Omitted/null means a campus post; a supplied association must remain positive.
    @Positive(message = "门店ID必须为正数")
    private Long shopId;
    @NotBlank(message = "标题不能为空")
    @Size(max = 255)
    private String title;
    // Campus posts may omit images; store-associated posts retain the legacy requirement.
    @Size(max = 2048)
    private String images;
    @NotBlank(message = "动态内容不能为空")
    @Size(max = 2048)
    private String content;

    @JsonIgnore
    @AssertTrue(message = "关联门店的动态图片不能为空")
    public boolean isStoreImageValid() {
        return shopId == null || (images != null && !images.trim().isEmpty());
    }
}
