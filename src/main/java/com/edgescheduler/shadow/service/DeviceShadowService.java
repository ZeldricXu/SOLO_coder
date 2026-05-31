package com.edgescheduler.shadow.service;

import com.edgescheduler.shadow.dto.DeviceShadowDTO;
import com.edgescheduler.shadow.entity.DeviceShadow;
import com.edgescheduler.shadow.entity.ShadowOperationLog;

import java.util.List;
import java.util.Map;

public interface DeviceShadowService {

    DeviceShadowDTO createShadow(String deviceKey);

    DeviceShadowDTO getShadow(String deviceKey);

    DeviceShadowDTO updateDesired(String deviceKey, Map<String, Object> desired, String operator);

    DeviceShadowDTO updateReported(String deviceKey, Map<String, Object> reported, String operator);

    DeviceShadowDTO mergeShadow(String deviceKey, Map<String, Object> state, String operator);

    DeviceShadowDTO syncShadow(String deviceKey);

    void deleteShadow(String deviceKey);

    Map<String, Object> calculateDelta(Map<String, Object> desired, Map<String, Object> reported);

    List<ShadowOperationLog> getOperationLogs(String deviceKey, int limit);

    DeviceShadowDTO getShadowStatus(String deviceKey);
}
