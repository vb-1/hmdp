package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.JwtUtils;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合 返回false
            return Result.fail("手机号格式错误");
        }
        //3. 符合 生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4. 保存验证码到session/redis
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);


        //5.发送验证码
        log.debug("发送验证码成功，验证码：{}", code);

        return Result.ok(code);
    }

    @Resource // 注入JwtUtils
    private JwtUtils jwtUtils;

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2.校验验证码
        String code = loginForm.getCode();
//        Object cachecode = session.getAttribute("code");
        String cachecode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cachecode == null || !cachecode.toString().equals(code)) {
            //3，不一致 报错
            return Result.fail("验证码错误");
        }
        //4. 一致，根据手机号查询用户  这里使用的是mybatis plus的单表查询
        User user = query().eq("phone", phone).one();

        // 5.判断用户是否存在
        if (user == null) {
            //用户不存在，创建新用户
            user = createUserWithPhone(phone);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // 1. 长令牌逻辑: 生成SessionID, 并在Redis中创建用户Session数据
        String sessionId = UUID.randomUUID().toString(true); // SessionID 作为长令牌的核心标识
        String longTokenRedisKey = LOGIN_USER_KEY_PREFIX + sessionId;

        Map<String, Object> userMapForRedis = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> {
                            if (fieldValue == null) return null; // 防止null转为"null"
                            return fieldValue.toString();
                        }));

        stringRedisTemplate.opsForHash().putAll(longTokenRedisKey, userMapForRedis);
        stringRedisTemplate.expire(longTokenRedisKey, LOGIN_USER_TTL_DAYS, TimeUnit.DAYS);
        log.debug("长令牌 Session 已存入 Redis, Key: {}, 过期时间: {} 天", longTokenRedisKey, LOGIN_USER_TTL_DAYS);

        // 2. 短令牌逻辑: 根据用户ID和一个较短的过期时间如1天，生成短令牌 (JWT)
        String shortToken = jwtUtils.generateShortToken(userDTO);
        log.debug("短令牌 JWT 已生成, 过期时间: {} ms", jwtUtils.getShortTokenExpirationMillis());


        // 3. 用户登录服务对长短令牌分别加密得到数字签名后成功响应客户端，并下发长短令牌。
        //    长令牌：long_token = SessionID (不需要额外签名, SessionID本身是凭证)
        //    短令牌：short_token = 用户ID+用户登录设备ID+过期时间+数字签名 (JWT本身已包含这些)
        Map<String, String> tokenResultMap = new HashMap<>();
        tokenResultMap.put("long_token", sessionId); // 直接下发SessionID作为长令牌
        tokenResultMap.put("short_token", shortToken); // 下发JWT作为短令牌

        // 清除验证码
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

        log.info("用户 {} 登录成功. Long-token: {}, Short-token: {}", userDTO.getId(), sessionId, shortToken);
        return Result.ok(tokenResultMap);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //2.保存用户 mybatis plus 保存
        save(user);
        return user;
    }
    /**
     * 用户登出 (根据长令牌 SessionID 清理Redis中的Session)
     * @param longTokenValue 即 SessionID
     * @return Result
     */
    public Result logout(String longTokenValue) {
        if (StrUtil.isBlank(longTokenValue)) {
            return Result.fail("长令牌不能为空");
        }
        String longTokenRedisKey = LOGIN_USER_KEY_PREFIX + longTokenValue;
        Boolean deleted = stringRedisTemplate.delete(longTokenRedisKey);
        if (Boolean.TRUE.equals(deleted)) {
            log.info("用户登出成功，已删除Redis Session: {}", longTokenRedisKey);
            return Result.ok("登出成功");
        }
        log.warn("尝试登出失败或Session已过期/不存在: {}", longTokenRedisKey);
        return Result.ok("已登出或会话不存在"); // 或者 Result.fail("会话不存在")
    }
}
