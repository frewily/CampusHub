package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.config.SecurityConfig;
import io.github.frewily.campushub.dto.request.ShopSearchRequest;
import io.github.frewily.campushub.dto.response.ShopSearchItem;
import io.github.frewily.campushub.dto.response.ShopSearchPage;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.ResourceAuthorizationService;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import io.github.frewily.campushub.service.IShopService;
import io.github.frewily.campushub.service.ShopSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

@SpringBootTest(classes = ShopSearchHttpContractTest.TestApplication.class)
@AutoConfigureMockMvc
class ShopSearchHttpContractTest {

    private static final String LARGE_ID = "9007199254740993";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ShopSearchService shopSearchService;
    @MockBean
    private IShopService legacyShopService;
    @MockBean
    private StringRedisTemplate redisTemplate;
    @MockBean
    private AccountAccessMapper accountAccessMapper;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ShopSearchController.class, ShopController.class, SecurityConfig.class,
            SecurityErrorResponder.class, RedisTokenAuthenticationFilter.class,
            ResourceAuthorizationService.class, GlobalExceptionHandler.class})
    static class TestApplication {
    }

    @Test
    void anonymousSearchIsPublicUsesSpecificRouteAndReturnsOnlyPublicFields() throws Exception {
        when(shopSearchService.search(any(ShopSearchRequest.class))).thenReturn(searchPage());

        mockMvc.perform(get("/shop/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value(LARGE_ID))
                .andExpect(jsonPath("$.data.items[0].typeId").value("9007199254740994"))
                .andExpect(jsonPath("$.data.items[0].name").value("Campus Cafe"))
                .andExpect(jsonPath("$.data.items[0].distanceMeters").value(125.5))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.sort").value("id"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.items[0].merchantId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].x").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].y").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].sold").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].comments").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].createTime").doesNotExist());

        ArgumentCaptor<ShopSearchRequest> captor = ArgumentCaptor.forClass(ShopSearchRequest.class);
        verify(shopSearchService).search(captor.capture());
        assertEquals("id", captor.getValue().getSort());
        assertEquals(1, captor.getValue().getPage());
        assertEquals(10, captor.getValue().getSize());
        verifyNoInteractions(legacyShopService, redisTemplate, accountAccessMapper);
    }

    @Test
    void validCombinedFiltersAreBoundAndPassedToSearchService() throws Exception {
        when(shopSearchService.search(any(ShopSearchRequest.class))).thenReturn(searchPage());

        mockMvc.perform(get("/shop/search")
                        .param("keyword", "coffee")
                        .param("typeId", "3")
                        .param("minPrice", "10")
                        .param("maxPrice", "90")
                        .param("minScore", "35")
                        .param("x", "120.1")
                        .param("y", "30.2")
                        .param("radiusMeters", "1000")
                        .param("sort", "distance")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<ShopSearchRequest> captor = ArgumentCaptor.forClass(ShopSearchRequest.class);
        verify(shopSearchService).search(captor.capture());
        ShopSearchRequest request = captor.getValue();
        assertEquals("coffee", request.getKeyword());
        assertEquals(3L, request.getTypeId());
        assertEquals(10L, request.getMinPrice());
        assertEquals(90L, request.getMaxPrice());
        assertEquals(35, request.getMinScore());
        assertEquals(120.1, request.getX());
        assertEquals(30.2, request.getY());
        assertEquals(1000, request.getRadiusMeters());
        assertEquals("distance", request.getSort());
        assertEquals(2, request.getPage());
        assertEquals(5, request.getSize());
    }

    @Test
    void unknownQueryFieldsCannotInjectInternalCriteriaOrOwnership() throws Exception {
        when(shopSearchService.search(any(ShopSearchRequest.class))).thenReturn(searchPage());

        mockMvc.perform(get("/shop/search")
                        .param("keyword", "coffee")
                        .param("likeKeyword", "%admin%")
                        .param("offset", "900")
                        .param("merchantId", "77")
                        .param("sold", "999")
                        .param("x", "120")
                        .param("y", "30"))
                .andExpect(status().isOk());

        ArgumentCaptor<ShopSearchRequest> captor = ArgumentCaptor.forClass(ShopSearchRequest.class);
        verify(shopSearchService).search(captor.capture());
        ShopSearchRequest request = captor.getValue();
        assertEquals("coffee", request.getKeyword());
        assertEquals(120.0, request.getX());
        assertEquals(30.0, request.getY());
        BeanWrapper bean = new BeanWrapperImpl(request);
        assertFalse(bean.isReadableProperty("likeKeyword"));
        assertFalse(bean.isReadableProperty("offset"));
        assertFalse(bean.isReadableProperty("merchantId"));
        assertFalse(bean.isReadableProperty("sold"));
    }

    @ParameterizedTest
    @MethodSource("invalidSearchQueries")
    void invalidQueryReturnsTyped400WithoutCallingService(String[] parameters) throws Exception {
        MockHttpServletRequestBuilder request = get("/shop/search");
        for (int i = 0; i < parameters.length; i += 2) {
            request.param(parameters[i], parameters[i + 1]);
        }
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verifyNoInteractions(shopSearchService);
    }

    static Stream<Arguments> invalidSearchQueries() {
        return Stream.of(
                invalid("page", "0"),
                invalid("page", "501"),
                invalid("size", "0"),
                invalid("size", "51"),
                invalid("sort", "price desc, (select sleep(10))"),
                invalid("sort", "score_desc; DROP TABLE tb_shop"),
                invalid("sort", "distance"),
                invalid("x", "NaN", "y", "30"),
                invalid("x", "Infinity", "y", "30"),
                invalid("x", "120", "y", "-Infinity"),
                invalid("x", "120"),
                invalid("y", "30"),
                invalid("radiusMeters", "100"),
                invalid("radiusMeters", "0", "x", "120", "y", "30"),
                invalid("radiusMeters", "50001", "x", "120", "y", "30"),
                invalid("radiusMeters", "2.5", "x", "120", "y", "30"),
                invalid("minPrice", "-1"),
                invalid("maxPrice", "-1"),
                invalid("minScore", "-1"),
                invalid("minScore", "51"),
                invalid("keyword", String.join("", Collections.nCopies(81, "x"))),
                invalid("typeId", "0"),
                invalid("x", "181", "y", "30"),
                invalid("x", "-181", "y", "30"),
                invalid("x", "120", "y", "91"),
                invalid("x", "120", "y", "-91"),
                invalid("minPrice", "20", "maxPrice", "10")
        );
    }

    private static Arguments invalid(String... parameters) {
        return Arguments.of((Object) parameters);
    }

    @Test
    void serviceUnavailableResponseIsStructuredAndDoesNotExposeBackendDetails() throws Exception {
        when(shopSearchService.search(any(ShopSearchRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.SHOP_STATE_UNAVAILABLE));

        mockMvc.perform(get("/shop/search"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("SHOP_STATE_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorMsg").value("门店状态暂时无法确认，请稍后重试"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void transactionCreationFailureReturnsSafeServiceUnavailableResponse() throws Exception {
        when(shopSearchService.search(any(ShopSearchRequest.class)))
                .thenThrow(new CannotCreateTransactionException("synthetic private credentials detail"));

        mockMvc.perform(get("/shop/search"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("SHOP_STATE_UNAVAILABLE"))
                .andExpect(jsonPath("$.errorMsg").value("门店状态暂时无法确认，请稍后重试"))
                .andExpect(jsonPath("$.errorMsg").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("synthetic private credentials detail"))));
    }

    private static ShopSearchPage searchPage() {
        ShopSearchItem item = new ShopSearchItem();
        item.setId(LARGE_ID);
        item.setTypeId("9007199254740994");
        item.setName("Campus Cafe");
        item.setImages("/images/cafe.png");
        item.setArea("North Campus");
        item.setAddress("Library Road");
        item.setAvgPrice(42L);
        item.setScore(48);
        item.setOpenHours("08:00-22:00");
        item.setDistanceMeters(125.5);
        return new ShopSearchPage(Collections.singletonList(item), 21L, 1, 10, "id", true);
    }
}
