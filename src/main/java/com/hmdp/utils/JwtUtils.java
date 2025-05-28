package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtils {

    private static final Logger log = LoggerFactory.getLogger(JwtUtils.class);

    // !!! 生产环境中，密钥务必从配置文件或环境变量中获取，且应为强密钥 !!!
    // 可以使用 Keys.secretKeyFor(SignatureAlgorithm.HS256) 生成一个，然后存储起来
    @Value("${hmdp.jwt.secret:defaultSecretKeyForHmdpAppWhichShouldBeVeryLongAndSecure}") // 从配置文件读取密钥
    private String secretString;
    private SecretKey secretKey;

    @Value("${hmdp.jwt.short-token.expiration-millis:86400000}") // 默认1天 (24 * 60 * 60 * 1000 ms)
    private long shortTokenExpirationMillis;

    @Value("${hmdp.jwt.issuer:hmdp}")
    private String issuer;

    // 定义JWT中存储用户信息的Key
    public static final String CLAIM_KEY_USER_ID = "uid";
    public static final String CLAIM_KEY_NICKNAME = "nik";
    public static final String CLAIM_KEY_ICON = "icn";

    // 初始化SecretKey
    @javax.annotation.PostConstruct
    public void init() {
        // 确保密钥足够安全 (至少256位)
        if (secretString == null || secretString.length() < 32) {
            log.warn("JWT Secret is not configured or too short! Using a default generated key. THIS IS NOT SECURE FOR PRODUCTION.");
            this.secretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);
        } else {
            this.secretKey = Keys.hmacShaKeyFor(secretString.getBytes());
        }
    }

    /**
     * 根据用户信息和设备ID生成短令牌 (JWT)
     * @param userDTO 用户信息 DTO
     * @return JWT字符串
     */
    public String generateShortToken(UserDTO userDTO) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_KEY_USER_ID, userDTO.getId());
        claims.put(CLAIM_KEY_NICKNAME, userDTO.getNickName());
        if (userDTO.getIcon() != null && !userDTO.getIcon().isEmpty()) {
            claims.put(CLAIM_KEY_ICON, userDTO.getIcon());
        }

        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);
        long expMillis = nowMillis + shortTokenExpirationMillis;
        Date exp = new Date(expMillis);

        return Jwts.builder()
                .setClaims(claims)
                .setIssuer(issuer)  //签发者
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 校验短令牌并返回Claims
     * @param token JWT字符串
     * @return Claims对象，如果校验失败或过期则返回null
     */
    public Claims validateAndParseShortToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(secretKey)
                    .requireIssuer(issuer) // 校验签发者
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.debug("JWT token is expired: {}", e.getMessage());
            return e.getClaims(); // 对于过期的令牌，我们仍然可能需要读取其内容（例如用户ID）
        } catch (Exception e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return null; // 其他类型的JWT异常（如签名错误，格式错误）
        }
    }

    /**
     * 从Claims中提取UserDTO
     * @param claims
     * @return
     */
    public UserDTO getUserDtoFromClaims(Claims claims) {
        if (claims == null) {
            return null;
        }
        UserDTO userDTO = new UserDTO();
        userDTO.setId(claims.get(CLAIM_KEY_USER_ID, Long.class));
        userDTO.setNickName(claims.get(CLAIM_KEY_NICKNAME, String.class));
        userDTO.setIcon(claims.get(CLAIM_KEY_ICON, String.class));
        // String deviceId = claims.get(CLAIM_KEY_DEVICE_ID, String.class);
        // 你可以根据需要将deviceId也放入UserDTO或单独处理
        return userDTO;
    }

    /**
     * 检查令牌是否真的过期（不只是JWT库报告过期）
     * @param claims
     * @return
     */
    public boolean isTokenActuallyExpired(Claims claims) {
        if (claims == null || claims.getExpiration() == null) {
            return true;
        }
        return claims.getExpiration().before(new Date());
    }

    public long getShortTokenExpirationMillis() {
        return shortTokenExpirationMillis;
    }
}