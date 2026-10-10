package io.github.frewily.campushub.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.frewily.campushub.CampusHubApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Registration/annotation contract only: no requests, filters, DB/Redis or workers. */
class ApiInventoryContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
            "method", "path", "controller", "handler", "preAuthorize"));
    private static Set<String> actual;
    private static ArrayNode documented;
    private static Set<Class<?>> controllerTypes;

    @BeforeAll
    static void discoverProductionMappings() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        URL mainClasses = CampusHubApplication.class.getProtectionDomain().getCodeSource().getLocation();
        controllerTypes = new HashSet<>();
        List<Object> controllers = new ArrayList<>();
        for (BeanDefinition bean : scanner.findCandidateComponents("io.github.frewily.campushub")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            // Test probes must not masquerade as production routes, even after new package splits.
            if (!mainClasses.equals(type.getProtectionDomain().getCodeSource().getLocation())) continue;
            controllerTypes.add(type);
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            assertEquals(1, constructors.length, "update inventory fixture for multi-constructor controller " + type);
            Constructor<?> constructor = constructors[0];
            Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(p -> mock(p)).toArray();
            controllers.add(constructor.newInstance(dependencies));
        }
        assertFalse(controllers.isEmpty(), "production controller scan must not silently become empty");
        MockMvc mvc = standaloneSetup(controllers.toArray()).build();
        RequestMappingHandlerMapping mapping = mvc.getDispatcherServlet().getWebApplicationContext()
                .getBean(RequestMappingHandlerMapping.class);
        actual = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            assertFalse(methods.isEmpty(), "document unrestricted HTTP mappings explicitly rather than omit them");
            PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (guard == null) guard = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            for (RequestMethod method : methods) {
                for (String path : info.getPatternValues()) {
                    ObjectNode route = JSON.createObjectNode();
                    route.put("method", method.name()); route.put("path", path);
                    route.put("controller", handler.getBeanType().getSimpleName());
                    route.put("handler", handler.getMethod().getName());
                    route.put("preAuthorize", guard == null ? "" : guard.value());
                    assertTrue(actual.add(canonical(route)), "duplicate registered inventory row");
                }
            }
        }
        JsonNode inventory = JSON.readTree(Files.readAllBytes(Paths.get("docs/api/routes.json")));
        assertEquals(1, inventory.path("schema_version").asInt());
        assertTrue(inventory.path("source_revision").asText().matches("[0-9a-f]{40}"));
        assertTrue(inventory.path("routes").isArray());
        documented = (ArrayNode) inventory.get("routes");
    }

    @Test
    void inventoryMatchesEveryProductionMappingAndDeclaredMethodGuard() {
        assertEquals(actual, rows(documented));
    }

    @Test
    void endpointTablesMatchInventoryWithoutMissingExtraOrDuplicateRoutes() throws Exception {
        String markdown = new String(Files.readAllBytes(Paths.get("docs/api/endpoints.md")), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("(?m)^\\|\\s*(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s*\\|\\s*`([^`]+)`\\s*\\|").matcher(markdown);
        Set<String> table = new TreeSet<>();
        while (matcher.find()) assertTrue(table.add(matcher.group(1) + " " + matcher.group(2)), "duplicate endpoint table row");
        Set<String> expected = new TreeSet<>();
        for (JsonNode route : documented) expected.add(route.get("method").asText() + " " + route.get("path").asText());
        assertEquals(expected, table, "documentation tables must follow the documented method/path format");
    }

    @Test
    void missingExtraDuplicateAndChangedGuardAreRejected() {
        ArrayNode missing = documented.deepCopy(); missing.remove(0);
        assertNotEquals(actual, rows(missing));
        ArrayNode extra = documented.deepCopy();
        ObjectNode fake = ((ObjectNode) extra.get(0)).deepCopy(); fake.put("path", "/not-implemented"); extra.add(fake);
        assertNotEquals(actual, rows(extra));
        ArrayNode changed = documented.deepCopy(); ((ObjectNode) changed.get(0)).put("preAuthorize", "permitAll()");
        assertNotEquals(actual, rows(changed));
        ArrayNode duplicate = documented.deepCopy(); duplicate.add(duplicate.get(0).deepCopy());
        assertThrows(AssertionError.class, () -> rows(duplicate));
    }

    @Test
    void emptyCommentControllerDoesNotCreateACommentEndpoint() {
        assertTrue(controllerTypes.contains(BlogCommentsController.class));
        for (JsonNode route : documented) assertNotEquals("BlogCommentsController", route.get("controller").asText());
    }

    private static Set<String> rows(ArrayNode routes) {
        Set<String> result = new TreeSet<>();
        for (JsonNode route : routes) assertTrue(result.add(canonical(route)), "duplicate documented route");
        return result;
    }

    private static String canonical(JsonNode route) {
        Set<String> fields = new HashSet<>(); route.fieldNames().forEachRemaining(fields::add);
        assertEquals(FIELDS, fields, "unexpected inventory schema");
        for (String field : FIELDS) assertTrue(route.get(field).isTextual(), "inventory fields must be strings");
        // Serialize a fixed-order array; separator characters in future values cannot create collisions.
        ArrayNode tuple = JSON.createArrayNode();
        for (String field : Arrays.asList("method", "path", "controller", "handler", "preAuthorize")) tuple.add(route.get(field));
        return tuple.toString();
    }
}
