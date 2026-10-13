package io.github.frewily.campushub.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** Full replacement of editable content only; association is read from the stored post. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlogEditRequest {
    @NotBlank(message = "标题不能为空")
    @Size(max = 255)
    private String title;

    @Size(max = 2048)
    private String images;

    @NotBlank(message = "动态内容不能为空")
    @Size(max = 2048)
    private String content;
}
