package io.github.frewily.campushub.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import javax.validation.constraints.*;

@Data
@Validated
@ConfigurationProperties("campushub.images")
public class ImageStorageProperties {
    @NotBlank private String directory = "./data/uploads";
    @Min(1) @Max(10485760) private int maxBytes = 2097152;
    @Min(1) @Max(16000000) private long maxPixels = 16000000;
}
