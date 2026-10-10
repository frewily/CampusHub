package io.github.frewily.campushub.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlogCommentCreateRequest {
    @NotNull
    @Positive
    private Long blogId;

    @NotBlank
    @Size(max = 255)
    private String content;
}
