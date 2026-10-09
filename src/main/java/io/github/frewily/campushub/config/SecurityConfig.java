package io.github.frewily.campushub.config;

import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.security.RedisTokenAuthenticationFilter;
import io.github.frewily.campushub.security.SecurityErrorResponder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final RedisTokenAuthenticationFilter redisTokenAuthenticationFilter;
    private final SecurityErrorResponder securityErrorResponder;

    public SecurityConfig(RedisTokenAuthenticationFilter redisTokenAuthenticationFilter,
                          SecurityErrorResponder securityErrorResponder) {
        this.redisTokenAuthenticationFilter = redisTokenAuthenticationFilter;
        this.securityErrorResponder = securityErrorResponder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and().requestCache().disable()
                .formLogin().disable()
                .httpBasic().disable()
                .logout().disable()
                .exceptionHandling()
                .authenticationEntryPoint((request, response, exception) ->
                        securityErrorResponder.write(response, ErrorCode.AUTHENTICATION_FAILED, "请先登录"))
                .accessDeniedHandler((request, response, exception) ->
                        securityErrorResponder.write(response, ErrorCode.AUTHORIZATION_FAILED, "无权执行该操作"))
                .and().authorizeRequests()
                .antMatchers(HttpMethod.POST, "/user/code", "/user/login").permitAll()
                .antMatchers(HttpMethod.GET, "/blog/hot", "/shop/**", "/shop-type/**", "/voucher/**").permitAll()
                .antMatchers(HttpMethod.GET, "/health/live", "/health/ready", "/imgs/blogs/**").permitAll()
                .anyRequest().authenticated()
                .and().addFilterBefore(redisTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
