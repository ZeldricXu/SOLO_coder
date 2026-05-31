package com.device.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.device.platform.common.BusinessException;
import com.device.platform.common.DeviceStatus;
import com.device.platform.common.JsonUtils;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.DeviceAuthRequest;
import com.device.platform.dto.DeviceAuthResponse;
import com.device.platform.entity.Device;
import com.device.platform.entity.DeviceAuth;
import com.device.platform.mapper.DeviceAuthMapper;
import com.device.platform.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAuthService {

    private final DeviceMapper deviceMapper;
    private final DeviceAuthMapper deviceAuthMapper;

    @Value("${device.auth.token-expire-hours:24}")
    private int tokenExpireHours;

    @Transactional
    public Mono<DeviceAuthResponse> authenticate(DeviceAuthRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("deviceId", request.getDeviceId());

            Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                    .eq(Device::getDeviceId, request.getDeviceId()));

            if (device == null) {
                throw new BusinessException(401, "设备不存在或未注册", ctx.getTraceId());
            }

            if (device.getStatus() == DeviceStatus.DECOMMISSIONED ||
                device.getStatus() == DeviceStatus.INACTIVE ||
                device.getStatus() == DeviceStatus.SUSPENDED) {
                throw new BusinessException(403, "设备状态异常，无法认证", ctx.getTraceId());
            }

            String hashedSecret = hashSecret(request.getDeviceSecret());
            if (!hashedSecret.equals(device.getDeviceSecret())) {
                throw new BusinessException(401, "设备密钥错误", ctx.getTraceId());
            }

            String token = generateToken();
            String refreshToken = generateToken();
            String sessionId = generateSessionId();
            Instant expiresAt = Instant.now().plus(tokenExpireHours, ChronoUnit.HOURS);

            DeviceAuth auth = new DeviceAuth();
            auth.setDeviceId(request.getDeviceId());
            auth.setSessionId(sessionId);
            auth.setToken(token);
            auth.setRefreshToken(refreshToken);
            auth.setExpiresAt(expiresAt);
            auth.setLastAuthenticatedAt(Instant.now());
            auth.setAuthMethod(request.getAuthMethod() != null ? request.getAuthMethod() : "SECRET");
            auth.setClientIp(request.getClientIp());
            auth.setUserAgent(request.getUserAgent());
            auth.setRevoked(false);

            deviceAuthMapper.insert(auth);

            device.setLastHeartbeatAt(Instant.now());
            device.setStatus(DeviceStatus.ONLINE);
            deviceMapper.updateById(device);

            DeviceAuthResponse response = new DeviceAuthResponse();
            response.setDeviceId(request.getDeviceId());
            response.setToken(token);
            response.setRefreshToken(refreshToken);
            response.setExpiresAt(expiresAt);
            response.setSessionId(sessionId);

            log.info("设备认证成功: deviceId={}, sessionId={}, traceId={}",
                    request.getDeviceId(), sessionId, ctx.getTraceId());

            return response;
        });
    }

    public Mono<Boolean> validateToken(String token, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            if (token == null || token.isEmpty()) {
                return false;
            }

            DeviceAuth auth = deviceAuthMapper.selectOne(new LambdaQueryWrapper<DeviceAuth>()
                    .eq(DeviceAuth::getToken, token)
                    .eq(DeviceAuth::isRevoked, false));

            if (auth == null) {
                log.debug("Token不存在或已吊销: token={}, traceId={}", token, ctx.getTraceId());
                return false;
            }

            if (auth.getExpiresAt().isBefore(Instant.now())) {
                log.debug("Token已过期: deviceId={}, traceId={}", auth.getDeviceId(), ctx.getTraceId());
                return false;
            }

            return true;
        });
    }

    public Mono<String> getDeviceIdByToken(String token) {
        return Mono.fromCallable(() -> {
            DeviceAuth auth = deviceAuthMapper.selectOne(new LambdaQueryWrapper<DeviceAuth>()
                    .eq(DeviceAuth::getToken, token)
                    .eq(DeviceAuth::isRevoked, false));

            return auth != null ? auth.getDeviceId() : null;
        });
    }

    @Transactional
    public Mono<Void> revokeToken(String token, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            DeviceAuth auth = deviceAuthMapper.selectOne(new LambdaQueryWrapper<DeviceAuth>()
                    .eq(DeviceAuth::getToken, token));

            if (auth != null) {
                auth.setRevoked(true);
                deviceAuthMapper.updateById(auth);
                log.info("Token已吊销: deviceId={}, traceId={}", auth.getDeviceId(), ctx.getTraceId());
            }

            return null;
        });
    }

    @Transactional
    public Mono<DeviceAuthResponse> refreshToken(String refreshToken, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            DeviceAuth auth = deviceAuthMapper.selectOne(new LambdaQueryWrapper<DeviceAuth>()
                    .eq(DeviceAuth::getRefreshToken, refreshToken)
                    .eq(DeviceAuth::isRevoked, false));

            if (auth == null) {
                throw new BusinessException(401, "刷新令牌无效", ctx.getTraceId());
            }

            auth.setRevoked(true);
            deviceAuthMapper.updateById(auth);

            String newToken = generateToken();
            String newRefreshToken = generateToken();
            String newSessionId = generateSessionId();
            Instant expiresAt = Instant.now().plus(tokenExpireHours, ChronoUnit.HOURS);

            DeviceAuth newAuth = new DeviceAuth();
            newAuth.setDeviceId(auth.getDeviceId());
            newAuth.setSessionId(newSessionId);
            newAuth.setToken(newToken);
            newAuth.setRefreshToken(newRefreshToken);
            newAuth.setExpiresAt(expiresAt);
            newAuth.setLastAuthenticatedAt(Instant.now());
            newAuth.setAuthMethod("REFRESH_TOKEN");
            newAuth.setClientIp(auth.getClientIp());
            newAuth.setRevoked(false);

            deviceAuthMapper.insert(newAuth);

            DeviceAuthResponse response = new DeviceAuthResponse();
            response.setDeviceId(auth.getDeviceId());
            response.setToken(newToken);
            response.setRefreshToken(newRefreshToken);
            response.setExpiresAt(expiresAt);
            response.setSessionId(newSessionId);

            log.info("令牌刷新成功: deviceId={}, traceId={}", auth.getDeviceId(), ctx.getTraceId());

            return response;
        });
    }

    private String hashSecret(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new BusinessException(500, "密钥加密失败");
        }
    }

    private String generateToken() {
        return "dt_" + UUID.randomUUID().toString().replace("-", "") +
               UUID.randomUUID().toString().replace("-", "");
    }

    private String generateSessionId() {
        return "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
