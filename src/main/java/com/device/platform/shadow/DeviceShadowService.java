package com.device.platform.shadow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.device.platform.common.BusinessException;
import com.device.platform.common.JsonUtils;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.DeviceShadowResponse;
import com.device.platform.dto.DeviceShadowUpdateRequest;
import com.device.platform.entity.DeviceShadow;
import com.device.platform.mapper.DeviceShadowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceShadowService {

    private final DeviceShadowMapper deviceShadowMapper;

    @Transactional
    public Mono<DeviceShadowResponse> updateDesiredState(DeviceShadowUpdateRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("deviceId", request.getDeviceId());

            DeviceShadow shadow = getOrCreateShadow(request.getDeviceId());

            if (request.getVersion() != null && !request.getVersion().equals(shadow.getVersion())) {
                throw new BusinessException(409, "版本冲突，请获取最新状态后重试", ctx.getTraceId());
            }

            Map<String, Object> currentDesired = shadow.getDesiredState() != null ?
                    JsonUtils.fromJson(shadow.getDesiredState(), Map.class) : new HashMap<>();
            Map<String, Object> newDesired = request.getState();

            Map<String, Object> mergedDesired = new HashMap<>(currentDesired);
            mergedDesired.putAll(newDesired);

            shadow.setDesiredState(JsonUtils.toJson(mergedDesired));
            shadow.setVersion(shadow.getVersion() + 1);
            shadow.setDesiredUpdatedAt(Instant.now());
            shadow.setSyncPending(true);

            updateDeltaState(shadow);
            deviceShadowMapper.updateById(shadow);

            log.info("设备期望状态已更新: deviceId={}, version={}, traceId={}",
                    request.getDeviceId(), shadow.getVersion(), ctx.getTraceId());

            return buildShadowResponse(shadow);
        });
    }

    @Transactional
    public Mono<DeviceShadowResponse> updateReportedState(DeviceShadowUpdateRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("deviceId", request.getDeviceId());

            DeviceShadow shadow = getOrCreateShadow(request.getDeviceId());

            Map<String, Object> currentReported = shadow.getReportedState() != null ?
                    JsonUtils.fromJson(shadow.getReportedState(), Map.class) : new HashMap<>();
            Map<String, Object> newReported = request.getState();

            Map<String, Object> mergedReported = new HashMap<>(currentReported);
            mergedReported.putAll(newReported);

            shadow.setReportedState(JsonUtils.toJson(mergedReported));
            shadow.setReportedUpdatedAt(Instant.now());
            shadow.setSyncPending(false);
            shadow.setLastSyncError(null);

            updateDeltaState(shadow);
            deviceShadowMapper.updateById(shadow);

            log.debug("设备实际状态已更新: deviceId={}, traceId={}", request.getDeviceId(), ctx.getTraceId());

            return buildShadowResponse(shadow);
        });
    }

    public Mono<DeviceShadowResponse> getShadow(String deviceId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            DeviceShadow shadow = deviceShadowMapper.selectOne(new LambdaQueryWrapper<DeviceShadow>()
                    .eq(DeviceShadow::getDeviceId, deviceId));

            if (shadow == null) {
                throw new BusinessException(404, "设备影子不存在: " + deviceId, ctx.getTraceId());
            }

            return buildShadowResponse(shadow);
        });
    }

    @Transactional
    public Mono<Void> syncShadow(String deviceId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            DeviceShadow shadow = getOrCreateShadow(deviceId);

            if (!shadow.isSyncPending()) {
                log.debug("设备影子无需同步: deviceId={}", deviceId);
                return null;
            }

            try {
                Map<String, Object> desired = shadow.getDesiredState() != null ?
                        JsonUtils.fromJson(shadow.getDesiredState(), Map.class) : new HashMap<>();
                Map<String, Object> reported = shadow.getReportedState() != null ?
                        JsonUtils.fromJson(shadow.getReportedState(), Map.class) : new HashMap<>();

                boolean allSynced = reported.entrySet().containsAll(desired.entrySet());

                if (allSynced) {
                    shadow.setSyncPending(false);
                    shadow.setLastSyncError(null);
                    deviceShadowMapper.updateById(shadow);
                    log.info("设备影子同步完成: deviceId={}, traceId={}", deviceId, ctx.getTraceId());
                } else {
                    log.debug("设备影子同步中: deviceId={}, traceId={}", deviceId, ctx.getTraceId());
                }
            } catch (Exception e) {
                shadow.setLastSyncError(e.getMessage());
                deviceShadowMapper.updateById(shadow);
                log.error("设备影子同步失败: deviceId={}, error={}, traceId={}",
                        deviceId, e.getMessage(), ctx.getTraceId(), e);
                throw new BusinessException(500, "设备影子同步失败: " + e.getMessage(), ctx.getTraceId());
            }

            return null;
        });
    }

    @Transactional
    protected DeviceShadow getOrCreateShadow(String deviceId) {
        DeviceShadow shadow = deviceShadowMapper.selectOne(new LambdaQueryWrapper<DeviceShadow>()
                .eq(DeviceShadow::getDeviceId, deviceId));

        if (shadow == null) {
            shadow = new DeviceShadow();
            shadow.setDeviceId(deviceId);
            shadow.setVersion(1);
            shadow.setSyncPending(false);
            deviceShadowMapper.insert(shadow);
            log.info("创建设备影子: deviceId={}", deviceId);
        }

        return shadow;
    }

    private void updateDeltaState(DeviceShadow shadow) {
        try {
            Map<String, Object> desired = shadow.getDesiredState() != null ?
                    JsonUtils.fromJson(shadow.getDesiredState(), Map.class) : new HashMap<>();
            Map<String, Object> reported = shadow.getReportedState() != null ?
                    JsonUtils.fromJson(shadow.getReportedState(), Map.class) : new HashMap<>();

            Map<String, Object> delta = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : desired.entrySet()) {
                Object reportedValue = reported.get(entry.getKey());
                if (reportedValue == null || !entry.getValue().equals(reportedValue)) {
                    delta.put(entry.getKey(), Map.of(
                            "desired", entry.getValue(),
                            "reported", reportedValue
                    ));
                }
            }

            if (!delta.isEmpty()) {
                shadow.setDeltaState(JsonUtils.toJson(delta));
            } else {
                shadow.setDeltaState(null);
            }
        } catch (Exception e) {
            log.warn("计算状态差异失败: {}", e.getMessage());
        }
    }

    private DeviceShadowResponse buildShadowResponse(DeviceShadow shadow) {
        DeviceShadowResponse response = new DeviceShadowResponse();
        response.setDeviceId(shadow.getDeviceId());
        response.setVersion(shadow.getVersion());
        response.setDesiredUpdatedAt(shadow.getDesiredUpdatedAt());
        response.setReportedUpdatedAt(shadow.getReportedUpdatedAt());
        response.setSyncPending(shadow.isSyncPending());

        if (shadow.getDesiredState() != null) {
            response.setDesiredState(JsonUtils.fromJson(shadow.getDesiredState(), Map.class));
        }
        if (shadow.getReportedState() != null) {
            response.setReportedState(JsonUtils.fromJson(shadow.getReportedState(), Map.class));
        }
        if (shadow.getDeltaState() != null) {
            response.setDeltaState(JsonUtils.fromJson(shadow.getDeltaState(), Map.class));
        }

        return response;
    }

    @Transactional
    public Mono<Void> deleteShadow(String deviceId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            DeviceShadow shadow = deviceShadowMapper.selectOne(new LambdaQueryWrapper<DeviceShadow>()
                    .eq(DeviceShadow::getDeviceId, deviceId));

            if (shadow != null) {
                deviceShadowMapper.deleteById(shadow.getId());
                log.info("设备影子已删除: deviceId={}, traceId={}", deviceId, ctx.getTraceId());
            }

            return null;
        });
    }
}
