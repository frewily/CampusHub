package io.github.frewily.campushub.security;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.mapper.AccountAccessMapper;
import io.github.frewily.campushub.utils.RedisConstants;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.github.frewily.campushub.utils.RedisConstants.LOGIN_USER_KEY;

@Component
public class RedisTokenAuthenticationFilter extends OncePerRequestFilter {

    private final StringRedisTemplate stringRedisTemplate;
    private final AccountAccessMapper accountAccessMapper;

    public RedisTokenAuthenticationFilter(StringRedisTemplate stringRedisTemplate,
                                          AccountAccessMapper accountAccessMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.accountAccessMapper = accountAccessMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        UserHolder.removeUser();
        SecurityContextHolder.clearContext();
        try {
            authenticate(request);
            filterChain.doFilter(request, response);
        } finally {
            UserHolder.removeUser();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return;
        }
        String tokenKey = LOGIN_USER_KEY + token.trim();
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        if (userMap.isEmpty()) {
            return;
        }
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        if (user.getId() == null || !"ACTIVE".equals(accountAccessMapper.findAccountStatus(user.getId()))) {
            stringRedisTemplate.delete(tokenKey);
            return;
        }
        List<SimpleGrantedAuthority> authorities = accountAccessMapper.findRoles(user.getId()).stream()
                .map(AccountRole::fromDatabaseValue)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .map(AccountRole::authority)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        if (authorities.isEmpty()) {
            stringRedisTemplate.delete(tokenKey);
            return;
        }
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserHolder.saveUser(user);
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
    }
}
