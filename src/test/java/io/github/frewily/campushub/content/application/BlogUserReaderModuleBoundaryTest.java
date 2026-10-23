package io.github.frewily.campushub.content.application;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.mapper.UserMapper;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.service.impl.BlogServiceImpl;
import io.github.frewily.campushub.content.infrastructure.MyBatisBlogUserReader;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlogUserReaderModuleBoundaryTest {
    @Test
    void portIsPlainJavaAndExposesOnlyUserDtos() throws Exception {
        assertTrue(BlogUserReader.class.isInterface());
        assertNull(BlogUserReader.class.getSuperclass(), "Java reflection reports no superclass for interfaces");
        assertArrayEquals(new Class<?>[0], BlogUserReader.class.getInterfaces());
        assertFalse(BlogUserReader.class.isAnnotation());
        String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/io/github/frewily/campushub/content/application/BlogUserReader.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String forbidden : new String[]{"org.springframework", "com.baomidou", "cn.hutool", ".entity.", ".mapper.", ".utils.", ".service.", ".infrastructure."}) {
            assertFalse(source.contains(forbidden), "port must not depend on " + forbidden);
        }
        assertEquals(2, BlogUserReader.class.getDeclaredMethods().length);

        Method author = method("findAuthor", Long.class);
        assertEquals(UserDTO.class, author.getReturnType());
        Method likers = method("findLikers", List.class);
        assertEquals(List.class, likers.getReturnType());
        Type result = likers.getGenericReturnType();
        assertTrue(result instanceof ParameterizedType);
        assertEquals(UserDTO.class, ((ParameterizedType) result).getActualTypeArguments()[0]);
        for (Method method : BlogUserReader.class.getDeclaredMethods()) {
            assertFalse(User.class.isAssignableFrom(method.getReturnType()));
            assertFalse(User.class.isAssignableFrom(method.getParameterTypes()[0]));
            assertFalse(method.isAnnotationPresent(org.springframework.stereotype.Component.class));
        }
    }

    @Test
    void adapterDependsOnlyOnUserMapperAndBlogServiceDoesNotDependOnUserService() {
        Constructor<?>[] constructors = MyBatisBlogUserReader.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertArrayEquals(new Class<?>[]{UserMapper.class}, constructors[0].getParameterTypes());

        for (Field field : BlogServiceImpl.class.getDeclaredFields()) {
            assertNotEquals(IUserService.class, field.getType(), "BlogServiceImpl must use the narrow reader port");
        }
        assertTrue(java.util.Arrays.stream(BlogServiceImpl.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == BlogUserReader.class));
    }

    private static Method method(String name, Class<?> parameter) {
        try { return BlogUserReader.class.getMethod(name, parameter); }
        catch (NoSuchMethodException error) { throw new AssertionError(error); }
    }
}
