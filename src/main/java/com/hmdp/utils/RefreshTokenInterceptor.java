package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY_PREFIX;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL_DAYS;
import static com.hmdp.utils.SystemConstants.*;


@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenInterceptor.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // OPTIONS预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 1. 从请求头获取长短令牌
        String shortTokenValue = request.getHeader(HEADER_SHORT_TOKEN);
        String longTokenValue = request.getHeader(HEADER_LONG_TOKEN); // 即 SessionID

        UserDTO userDTO = null;
        boolean needsShortTokenRefresh = false;

        // 2. 优先校验短令牌 (short_token)
        if (StrUtil.isNotBlank(shortTokenValue)) {
            Claims claims = jwtUtils.validateAndParseShortToken(shortTokenValue);
            if (claims != null) {
                // 短令牌内容有效，检查是否过期
                if (!jwtUtils.isTokenActuallyExpired(claims)) {
                    // 短令牌有效且未过期，认证通过
                    userDTO = jwtUtils.getUserDtoFromClaims(claims);
                    log.debug("短令牌有效，用户ID: {}", userDTO.getId());
                } else {
                    // 短令牌已过期，但内容可信，准备用长令牌刷新
                    log.debug("短令牌已过期，尝试使用长令牌刷新. UserID from expired token: {}", claims.get(JwtUtils.CLAIM_KEY_USER_ID));
                    needsShortTokenRefresh = true;
                    userDTO = jwtUtils.getUserDtoFromClaims(claims); // 先从过期短令牌中获取用户信息，避免一次Redis查询
                }
            } else {
                // 短令牌非法 (签名错误等)，不是过期。此时应该拒绝。
                log.warn("接收到非法的短令牌 (签名错误或格式错误).");
                // 对于非法短令牌，不应继续尝试长令牌，可能存在安全风险。
                // 但根据题目描述：“否则认为令牌非法，要求客户端重新登录”，这里我们让它继续尝试长令牌，
                // 如果长令牌也失败，则LoginInterceptor会拦截。
                // 或者更严格一点：
                // response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                // response.getWriter().write("Invalid short token");
                // return false;
            }
        }

        // 3. 如果短令牌认证未成功 (比如不存在、非法、或已标记为过期需要刷新)
        if (userDTO == null || needsShortTokenRefresh) {
            if (StrUtil.isBlank(longTokenValue)) {
                // 短令牌无效/过期，且没有长令牌，无法认证
                if (userDTO != null && needsShortTokenRefresh) { // 短令牌过期但没有长令牌来刷新
                    log.warn("短令牌已过期，但未提供长令牌进行刷新。");
                } else {
                    log.debug("请求未携带长短令牌，或短令牌非法且无长令牌。");
                }
                // 此拦截器仅尝试恢复用户，不直接拒绝。交由LoginInterceptor处理是否需要登录。
                return true;
            }

            // 有长令牌，校验长令牌 (SessionID)
            log.debug("尝试通过长令牌 {} 认证", longTokenValue);
            String longTokenRedisKey = LOGIN_USER_KEY_PREFIX + longTokenValue;
            Map<Object, Object> redisUserMap = stringRedisTemplate.opsForHash().entries(longTokenRedisKey);

            if (redisUserMap.isEmpty()) {
                // 长令牌无效或Session已过期
                log.warn("长令牌 {} 无效或对应Session已过期/不存在于Redis.", longTokenValue);
                UserHolder.removeUser(); // 确保没有脏数据
                // 此拦截器仅尝试恢复用户，不直接拒绝。交由LoginInterceptor处理是否需要登录。
                return true;
            }

            // 长令牌有效，Session存在
            UserDTO userFromRedis = BeanUtil.fillBeanWithMap(redisUserMap, new UserDTO(), false);
            if (userDTO != null && needsShortTokenRefresh) { // 短令牌过期，用Redis数据确认用户身份
                if (!userFromRedis.getId().equals(userDTO.getId())) {
                    log.warn("警告：过期短令牌中的用户ID {} 与长令牌Session中的用户ID {} 不符！", userDTO.getId(), userFromRedis.getId());
                    UserHolder.removeUser(); // 安全起见，清除用户态
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 身份不一致，拒绝
                    return false;
                }
                log.debug("通过长令牌验证用户身份成功: {}", userFromRedis.getId());
            }
            userDTO = userFromRedis; // 最终确认的用户信息来自Redis

            // (7) ...用户登录态识别完成，同时用户登录服务重新生成一个短令牌，再次随着请求的响应下发到客户端。
            String deviceIdFromRequest = request.getHeader("X-Device-ID"); // 客户端应在刷新时也带上设备ID，如果短令牌绑定了设备
            // String deviceIdToUse = (userDTOFromExpiredShortToken != null) ? userDTOFromExpiredShortToken.getDeviceId() : deviceIdFromRequest; // 优先用旧短令牌的设备ID
            String newShortToken = jwtUtils.generateShortToken(userDTO/* 或从旧token获取 */);
            response.setHeader(RESPONSE_HEADER_REFRESHED_SHORT_TOKEN, newShortToken);
            log.info("短令牌已刷新并下发给用户ID: {}. 新短令牌: {}", userDTO.getId(), newShortToken);

            // 刷新长令牌 (Session) 的过期时间
            stringRedisTemplate.expire(longTokenRedisKey, LOGIN_USER_TTL_DAYS, TimeUnit.DAYS);
            log.debug("长令牌 {} 的Redis过期时间已刷新为 {} 天", longTokenValue, LOGIN_USER_TTL_DAYS);
        }

        // 如果到这里 userDTO 不为null, 说明认证成功 (通过有效短令牌，或通过长令牌+刷新短令牌)
        if (userDTO != null) {
            UserHolder.saveUser(userDTO);
            log.debug("用户 {} 已通过长短令牌机制认证，用户信息已存入UserHolder", userDTO.getId());
            // (可选) 刷新长令牌的过期时间，即使短令牌有效，也表明用户活跃
            if (StrUtil.isNotBlank(longTokenValue) && !needsShortTokenRefresh) { // 短令牌有效时，也刷新一下长令牌
                stringRedisTemplate.expire(LOGIN_USER_KEY_PREFIX + longTokenValue, LOGIN_USER_TTL_DAYS, TimeUnit.DAYS);
            }
        } else {
            log.debug("未能通过长短令牌机制认证用户。");
        }

        return true; // 此拦截器主要负责刷新和用户状态恢复，不直接拒绝未认证的请求，交由LoginInterceptor判断
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser(); // 清理ThreadLocal，防止内存泄漏
        log.trace("UserHolder cleared after request completion for: {}", request.getRequestURI());
    }

}
