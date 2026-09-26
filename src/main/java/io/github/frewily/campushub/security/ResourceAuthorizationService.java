package io.github.frewily.campushub.security;

import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("resourceAuthorization")
public class ResourceAuthorizationService {

    private final AccountAccessMapper accountAccessMapper;

    public ResourceAuthorizationService(AccountAccessMapper accountAccessMapper) {
        this.accountAccessMapper = accountAccessMapper;
    }

    public boolean canCreateShop(Long merchantId) {
        if (hasRole(AccountRole.ADMIN)) {
            return true;
        }
        return merchantId != null && hasRole(AccountRole.MERCHANT)
                && hasActiveMembership(merchantId);
    }

    public boolean canManageShop(Long shopId) {
        if (hasRole(AccountRole.ADMIN)) {
            return true;
        }
        if (shopId == null || !hasRole(AccountRole.MERCHANT)) {
            return false;
        }
        Long merchantId = accountAccessMapper.findMerchantIdByShopId(shopId);
        return merchantId != null && hasActiveMembership(merchantId);
    }

    public boolean canManagePromotion(Long shopId) {
        return canManageShop(shopId);
    }

    public boolean canParticipateAsUser() {
        return currentUserId() != null
                && !hasRole(AccountRole.ADMIN)
                && (hasRole(AccountRole.USER) || hasRole(AccountRole.MERCHANT));
    }

    private boolean hasActiveMembership(Long merchantId) {
        Long userId = currentUserId();
        return userId != null
                && accountAccessMapper.countActiveMerchantMembership(userId, merchantId) > 0;
    }

    private boolean hasRole(AccountRole role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> role.authority().equals(authority.getAuthority()));
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDTO)) {
            return null;
        }
        return ((UserDTO) authentication.getPrincipal()).getId();
    }
}
