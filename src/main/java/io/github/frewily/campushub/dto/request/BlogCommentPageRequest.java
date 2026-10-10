package io.github.frewily.campushub.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlogCommentPageRequest {
    @Positive
    private Long beforeId;

    @NotNull
    @Min(1)
    @Max(50)
    private Integer size = 20;
}
