package io.github.frewily.campushub.storage;

import io.github.frewily.campushub.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Only this application/administrator may write the root; it is not a shared writable directory. */
@Slf4j
@Component
@EnableConfigurationProperties(ImageStorageProperties.class)
public class LocalImageStorage implements ImageStorage {
    private static final Pattern NAME = Pattern.compile("/blogs/(?:[0-9]|1[0-5])/(?:[0-9]|1[0-5])/"
            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png)");
    private final Path root;
    private final int maxBytes;
    private final long maxPixels;

    public LocalImageStorage(ImageStorageProperties properties) {
        maxBytes = properties.getMaxBytes(); maxPixels = properties.getMaxPixels();
        try {
            Path configured = Paths.get(properties.getDirectory()).toAbsolutePath().normalize();
            if (configured.getParent() == null) throw new IOException("root directory not allowed");
            Files.createDirectories(configured);
            root = configured.toRealPath();
            if (Files.isSymbolicLink(configured)) throw new IOException("symbolic root not allowed");
        } catch (IOException error) {
            throw new IllegalStateException("Image storage directory is unavailable");
        }
    }
    @Override public String store(MultipartFile image) {
        if (image == null || image.isEmpty() || image.getSize() > maxBytes) throw invalid();
        byte[] input;
        try (InputStream stream = image.getInputStream()) { input = bounded(stream); }
        catch (IOException error) { throw unavailable(error); }
        String format;
        byte[] encoded;
        try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!"png".equals(format) && !"jpeg".equals(format)) throw invalid();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > maxPixels) throw invalid();
                BufferedImage decoded = reader.read(0);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(decoded, format, output) || output.size() > maxBytes) throw invalid();
                encoded = output.toByteArray(); // Discard metadata and trailing non-image payload.
            } finally { reader.dispose(); }
        } catch (IOException corrupt) { throw invalid(); }
        String id = UUID.randomUUID().toString(); int hash = id.hashCode();
        String name = "/blogs/" + (hash & 15) + "/" + ((hash >> 4) & 15) + "/" + id
                + ("jpeg".equals(format) ? ".jpg" : ".png");
        Path target = path(name);
        try {
            ensureParents(target);
            try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { output.write(encoded); }
        } catch (IOException error) { throw unavailable(error); }
        return name;
    }
    @Override public byte[] read(String name) {
        Path target = path(name);
        try {
            verifyParents(target);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new BusinessException(ErrorCode.NOT_FOUND);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw invalid();
            try (InputStream stream = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) { return bounded(stream); }
        } catch (IOException error) { throw unavailable(error); }
    }
    @Override public void delete(String name) {
        Path target = path(name);
        try {
            verifyParents(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw invalid();
            Files.deleteIfExists(target); // Exact file only; never recursively remove a directory.
        } catch (IOException error) { throw unavailable(error); }
    }
    private Path path(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw invalid();
        Path target = root.resolve(name.substring(1)).normalize();
        if (!target.startsWith(root)) throw invalid();
        return target;
    }
    private void verifyParents(Path target) throws IOException {
        Path current = root;
        if (Files.isSymbolicLink(current)) throw invalid();
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("storage root unavailable");
        for (Path part : root.relativize(target.getParent())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw invalid();
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("storage parent unavailable");
            }
        }
    }
    private void ensureParents(Path target) throws IOException {
        verifyParents(target); // Check existing components before any directory creation.
        Path current = root;
        for (Path part : root.relativize(target.getParent())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw invalid();
            try { Files.createDirectory(current); }
            catch (FileAlreadyExistsException existing) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("storage parent unavailable");
            }
        }
    }
    private byte[] bounded(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() > maxBytes - count) throw invalid();
            output.write(buffer, 0, count);
        }
        if (output.size() == 0) throw invalid();
        return output.toByteArray();
    }
    private BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED, "仅支持有效且符合大小限制的 JPEG/PNG 图片和受控图片路径"); }
    private BusinessException unavailable(IOException error) {
        log.warn("Local image storage failed, errorClass={}", error.getClass().getSimpleName());
        return new BusinessException(ErrorCode.IMAGE_STORAGE_UNAVAILABLE);
    }
}
