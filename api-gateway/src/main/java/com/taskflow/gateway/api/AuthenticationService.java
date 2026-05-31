package com.taskflow.gateway.api;

import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * 认证服务 - 最小化接口
 * 仅定义认证相关的核心操作
 */
public interface AuthenticationService {

    /**
     * 认证用户
     * @param username 用户名
     * @param password 密码
     * @return 认证结果（包含Token）
     */
    Mono<AuthenticationResult> authenticate(String username, String password);

    /**
     * 根据Token获取认证信息
     * @param token Token字符串
     * @return 认证信息
     */
    Mono<Authentication> getAuthentication(String token);

    /**
     * 登出（使Token失效）
     * @param token Token字符串
     */
    Mono<Void> logout(String token);

    /**
     * 认证结果
     */
    record AuthenticationResult(
            boolean success,
            String token,
            String username,
            String tenantId,
            String message
    ) {}
}
