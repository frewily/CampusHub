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
    void commentControllerRegistersExactlyTheDocumentedRoutesAndGuards() {
        assertTrue(controllerTypes.contains(BlogCommentsController.class));
        assertEquals(18, controllerTypes.size());
        assertEquals(51, actual.size());
        assertVoucherQueryRoute();
        assertBlogEditRoute();
        assertCommentRoute("POST", "/blog-comments", "createComment",
                "hasAnyRole('USER', 'MERCHANT', 'ADMIN')");
        assertCommentRoute("GET", "/blog-comments/of/blog/{blogId}", "listComments", "");
        assertCommentRoute("POST", "/blog-comments/{commentId}/replies", "createReply",
                "hasAnyRole('USER', 'MERCHANT', 'ADMIN')");
        assertCommentRoute("GET", "/blog-comments/{commentId}/replies", "listReplies", "");
        assertCommentRoute("DELETE", "/blog-comments/replies/{replyId}", "withdrawReply",
                "hasAnyRole('USER', 'MERCHANT', 'ADMIN')");
        assertCommentRoute("DELETE", "/blog-comments/{commentId}", "withdrawComment",
                "hasAnyRole('USER', 'MERCHANT', 'ADMIN')");
        ObjectNode adminRoute = JSON.createObjectNode();
        adminRoute.put("method", "POST"); adminRoute.put("path", "/admin/blog-comments/{commentId}/hide");
        adminRoute.put("controller", "AdminBlogCommentsController"); adminRoute.put("handler", "hideComment");
        adminRoute.put("preAuthorize", "hasRole('ADMIN')");
        assertTrue(actual.contains(canonical(adminRoute)), "admin moderation mapping/guard changed");
        assertTrue(rows(documented).contains(canonical(adminRoute)), "admin moderation route missing from inventory");
        ObjectNode replyHideRoute = JSON.createObjectNode();
        replyHideRoute.put("method", "POST");
        replyHideRoute.put("path", "/admin/blog-comments/replies/{replyId}/hide");
        replyHideRoute.put("controller", "AdminBlogCommentsController");
        replyHideRoute.put("handler", "hideReply");
        replyHideRoute.put("preAuthorize", "hasRole('ADMIN')");
        assertTrue(actual.contains(canonical(replyHideRoute)), "admin reply moderation mapping/guard changed");
        assertTrue(rows(documented).contains(canonical(replyHideRoute)), "admin reply moderation missing from inventory");
        ObjectNode auditRoute = JSON.createObjectNode();
        auditRoute.put("method", "GET"); auditRoute.put("path", "/admin/blog-comments/{commentId}/hide-audit");
        auditRoute.put("controller", "AdminBlogCommentsController"); auditRoute.put("handler", "queryHideAudit");
        auditRoute.put("preAuthorize", "hasRole('ADMIN')");
        assertTrue(actual.contains(canonical(auditRoute)), "admin hide-audit mapping/guard changed");
        assertTrue(rows(documented).contains(canonical(auditRoute)), "admin hide-audit route missing from inventory");
        ObjectNode replyAuditRoute = JSON.createObjectNode();
        replyAuditRoute.put("method", "GET");
        replyAuditRoute.put("path", "/admin/blog-comments/replies/{replyId}/hide-audit");
        replyAuditRoute.put("controller", "AdminBlogCommentsController");
        replyAuditRoute.put("handler", "queryReplyHideAudit");
        replyAuditRoute.put("preAuthorize", "hasRole('ADMIN')");
        assertTrue(actual.contains(canonical(replyAuditRoute)), "admin reply audit mapping/guard changed");
        assertTrue(rows(documented).contains(canonical(replyAuditRoute)), "admin reply audit missing from inventory");
        ObjectNode replyAuditPageRoute = JSON.createObjectNode();
        replyAuditPageRoute.put("method", "GET");
        replyAuditPageRoute.put("path", "/admin/blog-comments/replies/hide-audits/of/blog/{blogId}");
        replyAuditPageRoute.put("controller", "AdminBlogCommentsController");
        replyAuditPageRoute.put("handler", "listReplyHideAudits");
        replyAuditPageRoute.put("preAuthorize", "hasRole('ADMIN')");
        assertTrue(actual.contains(canonical(replyAuditPageRoute)), "admin reply audit page mapping/guard changed");
        assertTrue(rows(documented).contains(canonical(replyAuditPageRoute)), "admin reply audit page missing from inventory");
        ObjectNode auditPageRoute = JSON.createObjectNode();
        auditPageRoute.put("method", "GET");
        auditPageRoute.put("path", "/admin/blog-comments/hide-audits/of/blog/{blogId}");
        auditPageRoute.put("controller", "AdminBlogCommentsController");
        auditPageRoute.put("handler", "listHideAudits");
        auditPageRoute.put("preAuthorize", "hasRole('ADMIN')");
        assertTrue(actual.contains(canonical(auditPageRoute)), "admin hide-audit page mapping/guard changed");
        assertTrue(rows(documented).contains(canonical(auditPageRoute)), "admin hide-audit page missing from inventory");
        int actualCommentRouteCount = 0;
        for (String route : actual) {
            if (route.contains("\"BlogCommentsController\"")) actualCommentRouteCount++;
        }
        assertEquals(6, actualCommentRouteCount);
        int commentRouteCount = 0;
        for (JsonNode route : documented) {
            if ("BlogCommentsController".equals(route.path("controller").asText())) commentRouteCount++;
        }
        assertEquals(6, commentRouteCount);
        int adminCommentRouteCount = 0;
        for (String route : actual) {
            if (route.contains("\"AdminBlogCommentsController\"")) adminCommentRouteCount++;
        }
        assertEquals(6, adminCommentRouteCount);
        int documentedAdminCommentRouteCount = 0;
        for (JsonNode route : documented) {
            if ("AdminBlogCommentsController".equals(route.path("controller").asText())) {
                documentedAdminCommentRouteCount++;
            }
        }
        assertEquals(6, documentedAdminCommentRouteCount);
    }

    @Test
    void profileGetsMoveToTheDedicatedControllerWithoutChangingTheirHandlersOrGuards() {
        assertTrue(controllerTypes.contains(io.github.frewily.campushub.identity.api.UserProfileController.class));
        for (String[] mapping : new String[][]{
                {"/user/me", "me"},
                {"/user/info/{id}", "info"},
                {"/user/{id}", "queryUserById"}
        }) {
            ObjectNode expected = JSON.createObjectNode();
            expected.put("method", "GET");
            expected.put("path", mapping[0]);
            expected.put("controller", "UserProfileController");
            expected.put("handler", mapping[1]);
            expected.put("preAuthorize", "");
            assertTrue(actual.contains(canonical(expected)), "profile handler or guard changed for " + mapping[0]);
            assertTrue(rows(documented).contains(canonical(expected)), "profile route missing from inventory " + mapping[0]);
        }
        assertEquals(3L, actual.stream().filter(row -> row.contains("\"UserProfileController\"")).count());
        assertEquals(2L, actual.stream().filter(row -> row.contains("\"UserController\"")).count());
        ObjectNode logout = JSON.createObjectNode();
        logout.put("method", "POST"); logout.put("path", "/user/logout");
        logout.put("controller", "LogoutController"); logout.put("handler", "logout");
        logout.put("preAuthorize", "");
        assertTrue(actual.contains(canonical(logout)), "logout route must belong to the identity API");
        assertTrue(rows(documented).contains(canonical(logout)), "logout route missing from API inventory");
    }

    @Test
    void blogEditRouteIsRegisteredAndDocumentedWithItsRoleGuard() {
        assertBlogEditRoute();
    }

    @Test
    void checkInRoutesAreDiscoveredOutsideTheLegacyControllerPackage() {
        assertTrue(controllerTypes.contains(io.github.frewily.campushub.engagement.api.CheckInController.class));
        for (String[] mapping : new String[][]{{"POST", "/user/sign", "sign"}, {"GET", "/user/sign/count", "signCount"}}) {
            ObjectNode expected = JSON.createObjectNode();
            expected.put("method", mapping[0]); expected.put("path", mapping[1]);
            expected.put("controller", "CheckInController"); expected.put("handler", mapping[2]);
            expected.put("preAuthorize", "@resourceAuthorization.canParticipateAsUser()");
            assertTrue(actual.contains(canonical(expected)));
            assertTrue(rows(documented).contains(canonical(expected)));
        }
        assertEquals(2L, actual.stream().filter(row -> row.contains("\"CheckInController\"")).count());
    }

    @Test
    void followQueriesMoveWithoutChangingTheLegacyWriteRouteOrGuards() {
        assertTrue(controllerTypes.contains(io.github.frewily.campushub.social.api.FollowQueryController.class));
        for (String[] mapping : new String[][]{{"/follow/or/not/{id}", "isFollow"}, {"/follow/common/{id}", "common"}}) {
            ObjectNode expected = JSON.createObjectNode();
            expected.put("method", "GET"); expected.put("path", mapping[0]);
            expected.put("controller", "FollowQueryController"); expected.put("handler", mapping[1]); expected.put("preAuthorize", "");
            assertTrue(actual.contains(canonical(expected)));
            assertTrue(rows(documented).contains(canonical(expected)));
        }
        assertEquals(2L, actual.stream().filter(row -> row.contains("\"FollowQueryController\"")).count());
        assertEquals(1L, actual.stream().filter(row -> row.contains("\"FollowController\"")).count());
    }

    private static void assertBlogEditRoute() {
        ObjectNode expected = JSON.createObjectNode();
        expected.put("method", "PUT"); expected.put("path", "/blog/{id}");
        expected.put("controller", "BlogController"); expected.put("handler", "editBlog");
        expected.put("preAuthorize", "hasAnyRole('USER', 'MERCHANT', 'ADMIN')");
        assertTrue(actual.contains(canonical(expected)), "blog edit mapping/guard changed");
        assertTrue(rows(documented).contains(canonical(expected)), "blog edit mapping/guard missing from inventory");
        int controllerRouteCount = 0;
        for (String route : actual) if (route.contains("\"BlogController\"")) controllerRouteCount++;
        assertEquals(10, controllerRouteCount, "BlogController route count changed");
        expected.put("method", "DELETE"); expected.put("handler", "withdrawBlog");
        assertTrue(actual.contains(canonical(expected)), "blog withdrawal mapping/guard changed");
        assertTrue(rows(documented).contains(canonical(expected)), "withdrawal missing from inventory");
    }

    @Test
    void categoryReadIsStillPublicAfterMovingToTheMerchantPackage() {
        assertTrue(controllerTypes.contains(io.github.frewily.campushub.merchant.api.ShopTypeController.class));
        ObjectNode expected = JSON.createObjectNode();
        expected.put("method", "GET"); expected.put("path", "/shop-type/list");
        expected.put("controller", "ShopTypeController"); expected.put("handler", "queryTypeList");
        expected.put("preAuthorize", "");
        assertTrue(actual.contains(canonical(expected)));
        assertTrue(rows(documented).contains(canonical(expected)));
        assertEquals(1L, actual.stream().filter(row -> row.contains("\"ShopTypeController\"")).count());
    }

    private static void assertVoucherQueryRoute() {
        assertTrue(controllerTypes.contains(io.github.frewily.campushub.promotion.api.VoucherQueryController.class));
        ObjectNode expected = JSON.createObjectNode();
        expected.put("method", "GET"); expected.put("path", "/voucher/list/{shopId}");
        expected.put("controller", "VoucherQueryController"); expected.put("handler", "queryVoucherOfShop");
        expected.put("preAuthorize", "");
        assertTrue(actual.contains(canonical(expected)), "voucher catalog mapping/guard changed");
        assertTrue(rows(documented).contains(canonical(expected)), "voucher catalog route missing from inventory");
        assertEquals(1L, actual.stream().filter(row -> row.contains("\"VoucherQueryController\"")).count());
        assertEquals(2L, actual.stream().filter(row -> row.contains("\"VoucherController\"")).count());
    }

    private static void assertCommentRoute(String method, String path, String handler, String guard) {
        ObjectNode expected = JSON.createObjectNode();
        expected.put("method", method); expected.put("path", path);
        expected.put("controller", "BlogCommentsController"); expected.put("handler", handler);
        expected.put("preAuthorize", guard);
        assertTrue(actual.contains(canonical(expected)), "production mapping/guard changed for " + method + " " + path);
        assertTrue(rows(documented).contains(canonical(expected)), "documented mapping/guard missing for " + method + " " + path);
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
