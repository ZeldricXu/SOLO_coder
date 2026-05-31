package com.taskflow.gateway.api;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Token服务 - 最小化接口
 * 仅定义Token相关的核心操作
 */
public interface TokenService {

    /**
     * 生成Token
     * @param claims Token声明
     * @return 生成的Token字符串
     */
    String generateToken(Map<String, Object> claims);

    /**
     * 验证Token
     * @param token Token字符串
     * @return 是否有效
     */
    boolean validateToken(String token);

    /**
     * 从Token中获取声明
     * @param token Token字符串
     * @return Token声明
     */
    Map<String, Object> getClaims(String token);

    /**
     * 获取Token中的用户名
     * @param token Token字符串
     * @return 用户名
     */
    String getUsername(String token);

    /**
     * 获取Token中的租户ID
     * @param token Token字符串
     * @return 租户ID
     */
    String getTenantId(String token);

    /**
     * 刷新Token
     * @param oldToken 旧Token
     * @return 新Token（异步）
     */
    Mono<String> refreshToken(String oldToken);
}
