package io.github.frewily.campushub.dto.request;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class BlogCommentHideRequest {
    @NotBlank
    @Size(max = 255)
    private String reason;
}
