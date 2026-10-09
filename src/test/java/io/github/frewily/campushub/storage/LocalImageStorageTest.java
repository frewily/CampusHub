package io.github.frewily.campushub.storage;

import io.github.frewily.campushub.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalImageStorageTest {
    @TempDir Path directory;
    private ImageStorageProperties properties;
    private LocalImageStorage storage;
    @BeforeEach void setup() {
        properties = new ImageStorageProperties(); properties.setDirectory(directory.resolve("uploads").toString());
        storage = new LocalImageStorage(properties);
    }
    private byte[] picture(String format, int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, bytes));
        return bytes.toByteArray();
    }
    private MockMultipartFile file(byte[] bytes) { return new MockMultipartFile("file", "../../unsafe.html", "text/html", bytes); }
    @Test void jpegAndPngAreDetectedFromContentPersistedAndReadableAfterRestart() throws Exception {
        for (String format : Arrays.asList("png", "jpeg")) {
            String name = storage.store(file(picture(format, 2, 2)));
            assertTrue(name.startsWith("/blogs/")); assertTrue(name.endsWith(format.equals("png") ? ".png" : ".jpg"));
            Path actual = directory.resolve("uploads").resolve(name.substring(1));
            assertTrue(Files.exists(actual)); assertFalse(name.contains("unsafe"));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(storage.read(name))));
            assertArrayEquals(storage.read(name), new LocalImageStorage(properties).read(name));
            storage.delete(name); storage.delete(name); assertFalse(Files.exists(actual));
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(BusinessException.class, () -> storage.read(name)).getErrorCode());
        }
    }
    @Test void nonImagesSvgEmptyAndCorruptImagesAreRejectedWithoutWriting() throws Exception {
        for (byte[] bytes : Arrays.asList(new byte[0], "<svg onload='test'>".getBytes("UTF-8"),
                "<html>payload</html>".getBytes("UTF-8"), Arrays.copyOf(picture("png", 2, 2), 30))) {
            assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(BusinessException.class, () -> storage.store(file(bytes))).getErrorCode());
        }
        try (java.util.stream.Stream<Path> files = Files.walk(directory.resolve("uploads"))) { assertEquals(1, files.count()); }
    }
    @Test void trailingPayloadIsRemovedByCanonicalReencoding() throws Exception {
        byte[] original = picture("png", 2, 2);
        ByteArrayOutputStream combined = new ByteArrayOutputStream(); combined.write(original); combined.write("UNTRUSTED_TRAILER".getBytes("UTF-8"));
        String name = storage.store(file(combined.toByteArray()));
        assertFalse(new String(storage.read(name), "ISO-8859-1").contains("UNTRUSTED_TRAILER"));
    }
    @Test void byteAndPixelBudgetsAreEnforcedBeforeFilePublication() throws Exception {
        properties.setMaxBytes(10); LocalImageStorage tiny = new LocalImageStorage(properties);
        assertThrows(BusinessException.class, () -> tiny.store(file(picture("png", 2, 2))));
        properties.setMaxBytes(2097152); properties.setMaxPixels(3);
        LocalImageStorage pixels = new LocalImageStorage(properties);
        assertThrows(BusinessException.class, () -> pixels.store(file(picture("png", 2, 2))));
    }
    @Test void traversalAbsolutePathsAndUncontrolledNamesCannotReadOrDeleteOutsideRoot() throws Exception {
        Path outside = directory.resolve("outside.txt"); Files.write(outside, new byte[]{1, 2});
        for (String name : Arrays.asList("../outside.txt", outside.toString(), "/blogs/1/1/../../outside.txt",
                "/blogs/1/1/a.svg", "/blogs/1/1", "/blogs/1/1/%2e%2e%2fsecret", "\\blogs\\1\\1\\x.png")) {
            assertThrows(BusinessException.class, () -> storage.read(name));
            assertThrows(BusinessException.class, () -> storage.delete(name));
        }
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(outside));
    }
    @Test void fileAndParentSymlinksAreRejectedWithoutTouchingTarget() throws Exception {
        String name = storage.store(file(picture("png", 2, 2)));
        Path actual = directory.resolve("uploads").resolve(name.substring(1));
        Path outside = directory.resolve("outside.png"); Files.write(outside, picture("png", 1, 1));
        Files.delete(actual); Files.createSymbolicLink(actual, outside);
        assertThrows(BusinessException.class, () -> storage.read(name));
        assertThrows(BusinessException.class, () -> storage.delete(name)); assertTrue(Files.exists(outside));
        Files.delete(actual); Path parent = actual.getParent(); Files.delete(parent);
        Files.createSymbolicLink(parent, directory);
        assertThrows(BusinessException.class, () -> storage.read(name)); assertThrows(BusinessException.class, () -> storage.delete(name));
        assertTrue(Files.exists(outside));
    }
    @Test void unwritablePathIsTypedUnavailableRatherThanClaimedSuccess() throws Exception {
        String name = storage.store(file(picture("png", 2, 2)));
        Path root = directory.resolve("uploads");
        Path saved = directory.resolve("saved-uploads"); Files.move(root, saved); Files.write(root, new byte[]{1});
        assertEquals(ErrorCode.IMAGE_STORAGE_UNAVAILABLE, assertThrows(BusinessException.class, () -> storage.read(name)).getErrorCode());
        assertEquals(ErrorCode.IMAGE_STORAGE_UNAVAILABLE, assertThrows(BusinessException.class,
                () -> storage.store(file(picture("png", 2, 2)))).getErrorCode());
    }
    @Test void symbolicShardDirectoryMustBeRejectedBeforeCreatingAnythingOutsideRoot() throws Exception {
        Path root = directory.resolve("uploads"); Files.createDirectory(root.resolve("blogs"));
        Path outside = directory.resolve("outside-directory"); Files.createDirectory(outside);
        for (int i = 0; i < 16; i++) Files.createSymbolicLink(root.resolve("blogs").resolve(String.valueOf(i)), outside);
        assertThrows(BusinessException.class, () -> storage.store(file(picture("png", 2, 2))));
        try (java.util.stream.Stream<Path> files = Files.list(outside)) { assertEquals(0, files.count()); }
    }
}
