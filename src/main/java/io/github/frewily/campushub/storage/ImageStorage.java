package io.github.frewily.campushub.storage;

import org.springframework.web.multipart.MultipartFile;

public interface ImageStorage {
    String store(MultipartFile image);
    byte[] read(String name);
    void delete(String name);
}
