package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    //ServiceImpl<UserMapper, User> 继承了IService,由mybatis-plus提供，泛型中提供了实体类和mapper
    // 继承了IService，提供了一些方法，如save()、update()、remove()、getById()、list()、page()等
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送验证码成功，验证码：{}", code);
        //返回结果
        return Result.ok("发送验证码成功");

    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1 校验手机号
        String phone = loginForm.getPhone();
        if(phone == null || RegexUtils.isPhoneInvalid(phone)){
            //2 如果不一致，返回错误
            return Result.fail("手机号格式错误");
        }
        //3 校验验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String cacheCode = loginForm.getCode();
        if (code == null || !code.equals(cacheCode)) {
            //不一致，返回错误
            return Result.fail("验证码错误");
        }
        //4 根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5 如果不存在，创建新用户并保存
        if (user == null) {
                user = createUserWithPhone(phone);
        }
        //6 登录成功，保存用户信息到redis并返回结果
        //6.1 随机生成token,作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //6.2 将user对象转为hashmap对象存储到redis中
        // 通过糊涂包中的工具将 User 实体对象转换为 UserDTO 对象（脱敏处理，避免敏感信息泄露）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // Redis 的 Hash 结构存储需要 Map 格式，转换后才能通过 putAll() 方法将用户信息存入 Redis
        Map<String, Object> usermap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue != null ? fieldValue.toString() : "")
        );
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, usermap);
        //6.3 设置登录token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //6.4 返回token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4 获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();
        //5 写入Redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4 获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();
        //5 获取这个月的截止今天为止的所有签到记录, 返回的是一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0L) {
            return Result.ok(0);
        }
        //6 遍历循环
        int count = 0;
        while(true){
            //6.1 让这个数字与1做与运算，得到数字的最后一个bit位  //判断这个bit位是否为0
            if((num & 1) == 0L){
                //6.2 如果为0，说明未签到，结束
                break;
            }else {
                //6.3 如果不为0，说明已签到，计数器+1
                count++;
            }
            //6.4 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
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
        //保存用户
        save(user);
        return user;
    }
}
