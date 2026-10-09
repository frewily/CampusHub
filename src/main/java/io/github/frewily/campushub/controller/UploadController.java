package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.storage.ImageStorage;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@RestController
@RequestMapping("upload")
@Validated
public class UploadController {
    private final ImageStorage storage;
    public UploadController(ImageStorage storage) { this.storage = storage; }

    @PostMapping("blog")
    @PreAuthorize("hasAnyRole('USER', 'MERCHANT', 'ADMIN')")
    public Result uploadImage(@NotNull(message = "上传文件不能为空") @RequestParam("file") MultipartFile image) {
        return Result.ok(storage.store(image));
    }

    @GetMapping("/blog/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public Result deleteBlogImg(@NotBlank(message = "文件名不能为空") @RequestParam("name") String filename) {
        storage.delete(filename);
        return Result.ok();
    }

}
