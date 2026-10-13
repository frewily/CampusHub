package io.github.frewily.campushub.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.frewily.campushub.dto.request.BlogCreateRequest;
import io.github.frewily.campushub.entity.Blog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CampusTextPostRequestTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void campusImagesMayBeOmittedNullEmptyOrBlankAndMapToEmptyString() throws Exception {
        String[] bodies = {
                "{\"title\":\"Campus\",\"content\":\"Hello\"}",
                "{\"shopId\":null,\"title\":\"Campus\",\"images\":null,\"content\":\"Hello\"}",
                "{\"title\":\"Campus\",\"images\":\"\",\"content\":\"Hello\"}",
                "{\"title\":\"Campus\",\"images\":\" \\t \",\"content\":\"Hello\"}"
        };

        for (String body : bodies) {
            BlogCreateRequest request = JSON.readValue(body, BlogCreateRequest.class);
            assertTrue(validator.validate(request).isEmpty(), body);
            assertEquals("", ApiModelMapper.toBlog(request).getImages(), body);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"images\":null,", "\"images\":\"\",", "\"images\":\" \\t \",", ""})
    void storePostStillRequiresImagesEvenWhenClientClaimsComputedGuardIsTrue(String imagesField) throws Exception {
        String body = "{\"shopId\":11," + imagesField
                + "\"title\":\"Cafe\",\"content\":\"Good\",\"storeImageValid\":true}";
        BlogCreateRequest request = JSON.readValue(body, BlogCreateRequest.class);
        Set<?> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
        assertFalse(request.isStoreImageValid());
    }

    @Test
    void imageLengthIsCheckedBeforeCampusNormalizationAndNonblankValueIsPreserved() throws Exception {
        String tooLongBlank = repeat(' ', 2049);
        BlogCreateRequest invalid = JSON.readValue("{\"title\":\"Campus\",\"images\":\""
                + tooLongBlank + "\",\"content\":\"Hello\"}", BlogCreateRequest.class);
        assertFalse(validator.validate(invalid).isEmpty());

        String original = "  /imgs/campus.jpg  ";
        BlogCreateRequest campus = JSON.readValue("{\"title\":\"Campus\",\"images\":\""
                + original + "\",\"content\":\"Hello\"}", BlogCreateRequest.class);
        assertTrue(validator.validate(campus).isEmpty());
        assertEquals(original, ApiModelMapper.toBlog(campus).getImages());

        BlogCreateRequest store = JSON.readValue("{\"shopId\":11,\"title\":\"Cafe\",\"images\":\""
                + original + "\",\"content\":\"Good\"}", BlogCreateRequest.class);
        assertTrue(validator.validate(store).isEmpty());
        assertEquals(original, ApiModelMapper.toBlog(store).getImages());
    }

    @Test
    void titleContentAndShopIdHonorValidationBoundaries() throws Exception {
        BlogCreateRequest boundary = JSON.readValue("{\"shopId\":9223372036854775807,\"title\":\""
                + repeat('t', 255) + "\",\"content\":\"" + repeat('c', 2048)
                + "\",\"images\":\"cover\"}", BlogCreateRequest.class);
        assertTrue(validator.validate(boundary).isEmpty());
        assertEquals(Long.MAX_VALUE, ApiModelMapper.toBlog(boundary).getShopId());

        for (String body : new String[]{
                "{\"title\":\" \",\"content\":\"text\"}",
                "{\"title\":\"" + repeat('t', 256) + "\",\"content\":\"text\"}",
                "{\"title\":\"title\",\"content\":\" \"}",
                "{\"title\":\"title\",\"content\":\"" + repeat('c', 2049) + "\"}",
                "{\"shopId\":0,\"title\":\"title\",\"content\":\"text\"}",
                "{\"shopId\":-1,\"title\":\"title\",\"content\":\"text\"}"}) {
            BlogCreateRequest request = JSON.readValue(body, BlogCreateRequest.class);
            assertFalse(validator.validate(request).isEmpty(), body);
        }
        assertThrows(Exception.class, () -> JSON.readValue(
                "{\"shopId\":9223372036854775808,\"title\":\"title\",\"content\":\"text\"}",
                BlogCreateRequest.class));
    }

    @Test
    void mapperOnlyCopiesClientWritableFieldsAndSetsServerManagedDefaults() throws Exception {
        BlogCreateRequest request = JSON.readValue("{\"shopId\":null,\"title\":\"Campus\","
                + "\"images\":null,\"content\":\"Hello\",\"id\":999,\"userId\":88,"
                + "\"liked\":777,\"comments\":666,\"name\":\"Fake\",\"icon\":\"fake.png\","
                + "\"createTime\":\"2000-01-01T00:00:00\",\"updateTime\":\"2000-01-01T00:00:00\","
                + "\"extra\":true}", BlogCreateRequest.class);
        assertTrue(validator.validate(request).isEmpty());

        Blog blog = ApiModelMapper.toBlog(request);
        assertNull(blog.getId());
        assertNull(blog.getUserId());
        assertNull(blog.getName());
        assertNull(blog.getIcon());
        assertNull(blog.getCreateTime());
        assertNull(blog.getUpdateTime());
        assertEquals(0, blog.getLiked());
        assertEquals(0, blog.getComments());
        assertNull(blog.getShopId());
        assertEquals("Campus", blog.getTitle());
        assertEquals("", blog.getImages());
        assertEquals("Hello", blog.getContent());
    }

    private static String repeat(char value, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) text.append(value);
        return text.toString();
    }
}
