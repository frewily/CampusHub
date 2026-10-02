package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.config.GlobalExceptionHandler;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.*;
import io.github.frewily.campushub.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Exercises JSON binding, validation and the entities handed to persistence services. */
@ExtendWith(MockitoExtension.class)
class ApiModelHttpContractTest {
    private static final String SHOP = "\"name\":\"Campus Cafe\",\"typeId\":1,\"merchantId\":3,"
            + "\"images\":\"/imgs/cafe.jpg\",\"address\":\"Campus\",\"x\":118,\"y\":30";
    private static final String VOUCHER = "\"shopId\":11,\"title\":\"Coupon\",\"payValue\":100,\"actualValue\":200";
    private static final String TIMES = "\"stock\":10,\"beginTime\":\"2027-01-01T10:00:00\","
            + "\"endTime\":\"2027-01-01T11:00:00\"";
    @Mock private IShopService shopService;
    @Mock private IBlogService blogService;
    @Mock private IVoucherService voucherService;
    @Mock private IUserService userService;
    @Mock private IUserInfoService userInfoService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ShopController shop = new ShopController();
        BlogController blog = new BlogController();
        VoucherController voucher = new VoucherController();
        UserController user = new UserController();
        ReflectionTestUtils.setField(shop, "shopService", shopService);
        ReflectionTestUtils.setField(blog, "blogService", blogService);
        ReflectionTestUtils.setField(voucher, "voucherService", voucherService);
        ReflectionTestUtils.setField(user, "userService", userService);
        ReflectionTestUtils.setField(user, "userInfoService", userInfoService);
        mockMvc = standaloneSetup(shop, blog, voucher, user)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void createShopIgnoresClientIdsStatisticsAndTimestamps() throws Exception {
        when(shopService.createShop(any())).thenReturn(Result.ok(21L));
        mockMvc.perform(post("/shop").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + SHOP + ",\"id\":999,\"sold\":888,\"comments\":777,\"score\":50,"
                                + "\"distance\":100,\"createTime\":\"2000-01-01T00:00:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(21));
        ArgumentCaptor<Shop> captor = ArgumentCaptor.forClass(Shop.class);
        verify(shopService).createShop(captor.capture());
        Shop shop = captor.getValue();
        assertNull(shop.getId());
        assertNull(shop.getCreateTime());
        assertNull(shop.getDistance());
        assertEquals(0, shop.getSold());
        assertEquals(0, shop.getComments());
        assertEquals(0, shop.getScore());
        assertEquals(3L, shop.getMerchantId());
        assertEquals("Campus Cafe", shop.getName());
    }

    @Test
    void partialShopUpdateCannotChangeOwnershipOrStatistics() throws Exception {
        when(shopService.updateShop(any())).thenReturn(Result.ok());
        mockMvc.perform(put("/shop").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":11,\"name\":\"New name\",\"merchantId\":99,\"score\":50,"
                                + "\"sold\":888,\"comments\":777,\"updateTime\":\"2000-01-01T00:00:00\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<Shop> captor = ArgumentCaptor.forClass(Shop.class);
        verify(shopService).updateShop(captor.capture());
        Shop shop = captor.getValue();
        assertEquals(11L, shop.getId());
        assertEquals("New name", shop.getName());
        assertNull(shop.getMerchantId());
        assertNull(shop.getScore());
        assertNull(shop.getSold());
        assertNull(shop.getComments());
        assertNull(shop.getUpdateTime());
        assertNull(shop.getAddress());
    }

    @Test
    void publishBlogIgnoresClientAuthorCountersAndPrimaryKey() throws Exception {
        when(blogService.saveBlog(any())).thenReturn(Result.ok(22L));
        mockMvc.perform(post("/blog").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":11,\"title\":\"Lunch\",\"images\":\"/imgs/lunch.jpg\",\"content\":\"Good\","
                                + "\"id\":999,\"userId\":99,\"liked\":888,\"comments\":777,\"name\":\"Fake\","
                                + "\"createTime\":\"2000-01-01T00:00:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(22));
        ArgumentCaptor<Blog> captor = ArgumentCaptor.forClass(Blog.class);
        verify(blogService).saveBlog(captor.capture());
        Blog blog = captor.getValue();
        assertNull(blog.getId());
        assertNull(blog.getUserId());
        assertNull(blog.getName());
        assertNull(blog.getCreateTime());
        assertEquals(0, blog.getLiked());
        assertEquals(0, blog.getComments());
        assertEquals("Good", blog.getContent());
    }

    @Test
    void ordinaryVoucherIgnoresFlashSaleAndServerManagedFields() throws Exception {
        when(voucherService.addVoucher(any())).thenReturn(Result.ok(23L));
        mockMvc.perform(post("/voucher").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + VOUCHER + "," + TIMES + ",\"id\":999,\"type\":1,\"status\":3,"
                                + "\"createTime\":\"2000-01-01T00:00:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(23));
        ArgumentCaptor<Voucher> captor = ArgumentCaptor.forClass(Voucher.class);
        verify(voucherService).addVoucher(captor.capture());
        Voucher voucher = captor.getValue();
        assertNull(voucher.getId());
        assertNull(voucher.getStock());
        assertNull(voucher.getBeginTime());
        assertNull(voucher.getCreateTime());
        assertEquals(0, voucher.getType());
        assertEquals(1, voucher.getStatus());
    }

    @Test
    void flashSaleKeepsTermsAndReturnsServerGeneratedId() throws Exception {
        doAnswer(invocation -> {
            Voucher incoming = invocation.getArgument(0);
            assertNull(incoming.getId());
            incoming.setId(24L);
            return null;
        }).when(voucherService).addSeckillVoucher(any());
        mockMvc.perform(post("/voucher/seckill").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + VOUCHER + "," + TIMES + ",\"id\":999,\"type\":0,\"status\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(24));
        ArgumentCaptor<Voucher> captor = ArgumentCaptor.forClass(Voucher.class);
        verify(voucherService).addSeckillVoucher(captor.capture());
        Voucher voucher = captor.getValue();
        assertEquals(1, voucher.getType());
        assertEquals(1, voucher.getStatus());
        assertEquals(10, voucher.getStock());
        assertEquals(LocalDateTime.of(2027, 1, 1, 10, 0), voucher.getBeginTime());
        assertEquals(100L, voucher.getPayValue());
    }

    @Test
    void profileResponsePreservesLegacyFieldsWithoutMutatingEntity() throws Exception {
        LocalDateTime created = LocalDateTime.of(2020, 1, 1, 0, 0);
        UserInfo info = new UserInfo().setUserId(7L).setCity("Xuancheng").setIntroduce("Hello")
                .setFans(3).setFollowee(2).setCredits(10).setLevel(false)
                .setCreateTime(created).setUpdateTime(created);
        when(userInfoService.getById(7L)).thenReturn(info);
        mockMvc.perform(get("/user/info/7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.city").value("Xuancheng"))
                .andExpect(jsonPath("$.data.credits").value(10))
                .andExpect(jsonPath("$.data.createTime").doesNotExist())
                .andExpect(jsonPath("$.data.updateTime").doesNotExist());
        assertEquals(created, info.getCreateTime());
        assertEquals(created, info.getUpdateTime());
    }

    @Test
    void missingProfileKeepsEmptySuccessResponse() throws Exception {
        mockMvc.perform(get("/user/info/8"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidWriteFailsBeforeCallingBusinessService(String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = "PUT".equals(method) ? put(path) : post(path);
        mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verifyNoInteractions(shopService, blogService, voucherService);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("POST", "/shop", "{}"),
                Arguments.of("POST", "/shop", "{" + SHOP.replace("Campus Cafe", " ") + "}"),
                Arguments.of("POST", "/shop", "{" + SHOP.replace("\"x\":118", "\"x\":181") + "}"),
                Arguments.of("POST", "/shop", "{" + SHOP.replace("\"x\":118", "\"x\":-1") + "}"),
                Arguments.of("PUT", "/shop", "{\"id\":11,\"y\":-1}"),
                Arguments.of("PUT", "/shop", "{\"name\":\"Cafe\"}"),
                Arguments.of("PUT", "/shop", "{\"id\":-1}"),
                Arguments.of("PUT", "/shop", "{\"id\":11,\"name\":\" \"}"),
                Arguments.of("POST", "/blog", "{\"shopId\":11,\"title\":\"Lunch\"}"),
                Arguments.of("POST", "/voucher", "{" + VOUCHER.replace("\"payValue\":100", "\"payValue\":-1") + "}"),
                Arguments.of("POST", "/voucher", "{" + VOUCHER.replace("Coupon", " ") + "}"),
                Arguments.of("POST", "/voucher/seckill", "{" + VOUCHER + "}"),
                Arguments.of("POST", "/voucher/seckill", "{" + VOUCHER + "," + TIMES.replace("\"stock\":10", "\"stock\":0") + "}"),
                Arguments.of("POST", "/voucher/seckill", "{" + VOUCHER + "," + TIMES.replace("11:00:00", "09:00:00") + "}"),
                Arguments.of("POST", "/voucher/seckill", "{" + VOUCHER + "," + TIMES.replace("11:00:00", "10:00:00") + "}")
        );
    }
}
