package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.storage.ImageStorage;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ImageController {
    private final ImageStorage storage;
    public ImageController(ImageStorage storage) { this.storage = storage; }
    @GetMapping("/imgs/blogs/{first}/{second}/{filename}")
    public ResponseEntity<byte[]> image(@PathVariable String first, @PathVariable String second, @PathVariable String filename) {
        byte[] bytes = storage.read("/blogs/" + first + "/" + second + "/" + filename);
        return ResponseEntity.ok().contentType(filename.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
