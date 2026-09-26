package io.github.frewily.campushub.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.frewily.campushub.dto.LoginFormDTO;
import io.github.frewily.campushub.dto.Result;
import io.github.frewily.campushub.dto.UserDTO;
import io.github.frewily.campushub.entity.User;
import io.github.frewily.campushub.exception.BusinessException;
import io.github.frewily.campushub.exception.ErrorCode;
import io.github.frewily.campushub.mapper.UserMapper;
import io.github.frewily.campushub.service.IUserService;
import io.github.frewily.campushub.utils.RegexUtils;
import io.github.frewily.campushub.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.frewily.campushub.utils.RedisConstants.*;
import static io.github.frewily.campushub.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private static final DefaultRedisScript<Long> LOGIN_CODE_CONSUME_SCRIPT;
    private static final DefaultRedisScript<Long> LOGIN_FAILURE_INCREMENT_SCRIPT;

    static {
        LOGIN_CODE_CONSUME_SCRIPT = new DefaultRedisScript<>();
        LOGIN_CODE_CONSUME_SCRIPT.setLocation(new ClassPathResource("login-code-consume.lua"));
        LOGIN_CODE_CONSUME_SCRIPT.setResultType(Long.class);

        LOGIN_FAILURE_INCREMENT_SCRIPT = new DefaultRedisScript<>();
        LOGIN_FAILURE_INCREMENT_SCRIPT.setLocation(new ClassPathResource("login-failure-increment.lua"));
        LOGIN_FAILURE_INCREMENT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "手机号格式错误");
        }
        String cooldownKey = LOGIN_CODE_COOLDOWN_KEY + phone;
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                cooldownKey,
                "1",
                LOGIN_CODE_COOLDOWN_TTL,
                TimeUnit.SECONDS
        );
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "验证码发送过于频繁，请稍后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        try {
            stringRedisTemplate.opsForValue().set(
                    LOGIN_CODE_KEY + phone,
                    code,
                    LOGIN_CODE_TTL,
                    TimeUnit.MINUTES
            );
        } catch (RuntimeException exception) {
            stringRedisTemplate.delete(cooldownKey);
            throw exception;
        }
        return Result.ok("发送验证码成功");
    }

    @Override
    @Transactional
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        if (phone == null || RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "手机号格式错误");
        }
        String failureKey = LOGIN_FAILURE_KEY + phone;
        String failureCount = stringRedisTemplate.opsForValue().get(failureKey);
        if (failureCount != null && Long.parseLong(failureCount) >= LOGIN_FAILURE_LIMIT) {
            throw loginRateLimited();
        }
        String cacheCode = loginForm.getCode();
        if (cacheCode == null) {
            throw recordLoginFailure(failureKey);
        }
        Long consumed = stringRedisTemplate.execute(
                LOGIN_CODE_CONSUME_SCRIPT,
                Collections.singletonList(LOGIN_CODE_KEY + phone),
                cacheCode
        );
        if (consumed == null) {
            throw new IllegalStateException("验证码校验未返回结果");
        }
        if (consumed != 1L) {
            throw recordLoginFailure(failureKey);
        }
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "账号不可用");
        }
        String token = UUID.randomUUID().toString(true);
        // 只将脱敏后的 UserDTO 写入会话缓存。
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // Redis Hash 使用字符串 Map，避免直接序列化完整用户实体。
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue != null ? fieldValue.toString() : "")
        );
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        stringRedisTemplate.delete(failureKey);
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "认证信息缺失");
        }
        stringRedisTemplate.delete(LOGIN_USER_KEY + token.trim());
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果。
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0L) {
            return Result.ok(0);
        }
        int count = 0;
        while(true){
            if((num & 1) == 0L){
                break;
            }else {
                count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * // UserDTO 有 3 个成员：id, nickName, icon
     * // 转换后的 Map 内容为：
     * {
     *     "id": 123,
     *     "nickName": "用户昵称",
     *     "icon": "头像URL"
     * }
     */


    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setStatus("ACTIVE");
        save(user);
        getBaseMapper().insertDefaultUserRole(user.getId());
        return user;
    }

    private BusinessException recordLoginFailure(String failureKey) {
        Long failures = stringRedisTemplate.execute(
                LOGIN_FAILURE_INCREMENT_SCRIPT,
                Collections.singletonList(failureKey),
                String.valueOf(TimeUnit.MINUTES.toSeconds(LOGIN_FAILURE_TTL))
        );
        if (failures == null) {
            throw new IllegalStateException("登录失败计数未返回结果");
        }
        if (failures >= LOGIN_FAILURE_LIMIT) {
            return loginRateLimited();
        }
        return new BusinessException(ErrorCode.AUTHENTICATION_FAILED, "验证码错误");
    }

    private BusinessException loginRateLimited() {
        return new BusinessException(ErrorCode.RATE_LIMITED, "登录失败次数过多，请稍后再试");
    }
}
