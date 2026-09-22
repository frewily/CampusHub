package io.github.frewily.campushub.controller;

import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.entity.Shop;
import io.github.frewily.campushub.service.IShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ShopControllerTest {

    @Mock
    private IShopService shopService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ShopController shopController = new ShopController();
        ReflectionTestUtils.setField(shopController, "shopService", shopService);
        mockMvc = standaloneSetup(shopController).build();
    }

    @Test
    void shouldReturnShopResultWithoutNestedEnvelope() throws Exception {
        Shop shop = new Shop().setId(1L).setName("Campus Cafe");
        when(shopService.queryById(1L)).thenReturn(Result.ok(shop));

        mockMvc.perform(get("/shop/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("Campus Cafe"))
                .andExpect(jsonPath("$.data.success").doesNotExist());
    }
}
