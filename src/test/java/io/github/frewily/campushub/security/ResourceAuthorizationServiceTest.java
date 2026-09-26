package io.github.frewily.campushub.security;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceAuthorizationServiceTest {

    @Mock
    private AccountAccessMapper accountAccessMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void merchantCanManageOnlyStoreOfActiveMembership() {
        authenticate(7L, AccountRole.MERCHANT);
        when(accountAccessMapper.findMerchantIdByShopId(11L)).thenReturn(3L);
        when(accountAccessMapper.countActiveMerchantMembership(7L, 3L)).thenReturn(1);
        when(accountAccessMapper.findMerchantIdByShopId(12L)).thenReturn(4L);
        when(accountAccessMapper.countActiveMerchantMembership(7L, 4L)).thenReturn(0);
        ResourceAuthorizationService service = new ResourceAuthorizationService(accountAccessMapper);

        assertTrue(service.canManageShop(11L));
        assertFalse(service.canManageShop(12L));
    }

    @Test
    void adminCanManagePlatformStoreButCannotParticipateAsUser() {
        authenticate(1L, AccountRole.ADMIN, AccountRole.USER);
        ResourceAuthorizationService service = new ResourceAuthorizationService(accountAccessMapper);

        assertTrue(service.canManageShop(99L));
        assertFalse(service.canParticipateAsUser());
        verify(accountAccessMapper, never()).findMerchantIdByShopId(99L);
    }

    @Test
    void merchantCannotCreateStoreForUnrelatedMerchant() {
        authenticate(7L, AccountRole.MERCHANT);
        when(accountAccessMapper.countActiveMerchantMembership(7L, 5L)).thenReturn(0);
        ResourceAuthorizationService service = new ResourceAuthorizationService(accountAccessMapper);

        assertFalse(service.canCreateShop(5L));
    }

    private void authenticate(Long userId, AccountRole... roles) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        java.util.List<SimpleGrantedAuthority> authorities = Arrays.stream(roles)
                .map(AccountRole::authority)
                .map(SimpleGrantedAuthority::new)
                .collect(java.util.stream.Collectors.toList());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        roles.length == 0 ? Collections.emptyList() : authorities)
        );
    }
}
