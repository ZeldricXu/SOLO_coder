package com.meshcontrol.sidecar.service;

import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.sidecar.dto.ConfigUpdateRequest;
import com.meshcontrol.sidecar.dto.SidecarInjectRequest;
import com.meshcontrol.sidecar.entity.SidecarInstance;
import com.meshcontrol.sidecar.mapper.InjectionPolicyMapper;
import com.meshcontrol.sidecar.mapper.SidecarConfigMapper;
import com.meshcontrol.sidecar.mapper.SidecarInstanceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SidecarLifecycleService - 资源泄漏测试")
class SidecarLifecycleServiceResourceTest {

    @Mock
    private SidecarInstanceMapper sidecarInstanceMapper;

    @Mock
    private SidecarConfigMapper sidecarConfigMapper;

    @Mock
    private InjectionPolicyMapper injectionPolicyMapper;

    @InjectMocks
    private SidecarLifecycleService sidecarLifecycleService;

    private SidecarInstance createTestSidecar(String sidecarId) {
        SidecarInstance sidecar = new SidecarInstance();
        sidecar.setSidecarId(sidecarId);
        sidecar.setPodName("test-pod-" + sidecarId);
        sidecar.setNamespace("default");
        sidecar.setServiceName("test-service");
        sidecar.setStatus("running");
        sidecar.setConfigVersion(1);
        sidecar.setResources(new HashMap<>());
        return sidecar;
    }

    @Test
    @DisplayName("injectSidecar - 空值校验")
    void injectSidecar_NullRequest_ShouldThrowException() {
        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.injectSidecar(null));
    }

    @Test
    @DisplayName("injectSidecar - 必填字段校验")
    void injectSidecar_RequiredFields_ShouldThrowException() {
        SidecarInjectRequest request = new SidecarInjectRequest();

        request.setPodName("");
        request.setNamespace("default");
        request.setServiceName("test");
        request.setVersion("v1");
        request.setConfigVersion(1);
        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.injectSidecar(request),
                "Should reject blank podName");

        request.setPodName("test-pod");
        request.setNamespace("");
        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.injectSidecar(request),
                "Should reject blank namespace");

        request.setNamespace("default");
        request.setServiceName("");
        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.injectSidecar(request),
                "Should reject blank serviceName");

        request.setServiceName("test-service");
        request.setVersion("");
        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.injectSidecar(request),
                "Should reject blank version");

        request.setVersion("v1");
        request.setConfigVersion(null);
        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.injectSidecar(request),
                "Should reject null configVersion");
    }

    @Test
    @DisplayName("injectSidecar - 长度校验")
    void injectSidecar_LengthValidation_ShouldThrowException() {
        SidecarInjectRequest request = new SidecarInjectRequest();
        request.setPodName("a".repeat(254));
        request.setNamespace("default");
        request.setServiceName("test");
        request.setVersion("v1");
        request.setConfigVersion(1);

        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.injectSidecar(request));
    }

    @Test
    @DisplayName("updateSidecarConfig - 空参数校验")
    void updateSidecarConfig_NullOrEmptyParams_ShouldThrowException() {
        SidecarInstance sidecar = createTestSidecar("sc-123");

        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.updateSidecarConfig(null, new ConfigUpdateRequest()),
                "Should reject null sidecarId");

        when(sidecarInstanceMapper.selectById("sc-123")).thenReturn(sidecar);

        ConfigUpdateRequest request = new ConfigUpdateRequest();
        request.setNamespace("default");
        request.setParameters(Collections.emptyMap());

        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.updateSidecarConfig("sc-123", request),
                "Should reject empty parameters");

        request.setParameters(null);
        assertThrows(BusinessException.class,
                () -> sidecarLifecycleService.updateSidecarConfig("sc-123", request),
                "Should reject null parameters");
    }

    @Test
    @DisplayName("validateSidecarInstance - 内部方法验证")
    void validateSidecarInstance_InternalValidation_ShouldThrowException() throws Exception {
        Method validateMethod = SidecarLifecycleService.class.getDeclaredMethod(
                "validateSidecarInstance", SidecarInstance.class);
        validateMethod.setAccessible(true);

        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(sidecarLifecycleService, (SidecarInstance) null),
                "Should reject null sidecar");

        SidecarInstance sidecar = new SidecarInstance();
        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(sidecarLifecycleService, sidecar),
                "Should reject sidecar with null podName");

        sidecar.setPodName("");
        sidecar.setSidecarId("sc-123");
        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(sidecarLifecycleService, sidecar),
                "Should reject sidecar with blank podName");

        sidecar.setPodName("test-pod");
        sidecar.setSidecarId(null);
        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(sidecarLifecycleService, sidecar),
                "Should reject sidecar with null sidecarId");
    }

    @Test
    @DisplayName("cleanup - @PreDestroy方法应清除所有连接")
    void cleanup_ShutdownHook_ShouldClearAllConnections() throws Exception {
        Field activeConnectionsField = SidecarLifecycleService.class.getDeclaredField("activeConnections");
        activeConnectionsField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, HttpURLConnection> activeConnections =
                (ConcurrentHashMap<String, HttpURLConnection>) activeConnectionsField.get(sidecarLifecycleService);

        HttpURLConnection mockConn1 = mock(HttpURLConnection.class);
        HttpURLConnection mockConn2 = mock(HttpURLConnection.class);
        activeConnections.put("conn-1", mockConn1);
        activeConnections.put("conn-2", mockConn2);

        assertEquals(2, activeConnections.size());

        sidecarLifecycleService.cleanup();

        verify(mockConn1, times(1)).disconnect();
        verify(mockConn2, times(1)).disconnect();
        assertTrue(activeConnections.isEmpty());
    }

    @Test
    @DisplayName("removeSidecar - 应移除并断开关联连接")
    void removeSidecar_ShouldDisconnectAndRemoveConnection() throws Exception {
        SidecarInstance sidecar = createTestSidecar("sc-123");
        when(sidecarInstanceMapper.selectById("sc-123")).thenReturn(sidecar);
        when(sidecarInstanceMapper.deleteById("sc-123")).thenReturn(1);

        Field activeConnectionsField = SidecarLifecycleService.class.getDeclaredField("activeConnections");
        activeConnectionsField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, HttpURLConnection> activeConnections =
                (ConcurrentHashMap<String, HttpURLConnection>) activeConnectionsField.get(sidecarLifecycleService);

        HttpURLConnection mockConn = mock(HttpURLConnection.class);
        activeConnections.put("conn_sc-123", mockConn);

        boolean result = sidecarLifecycleService.removeSidecar("sc-123");

        assertTrue(result);
        verify(mockConn, times(1)).disconnect();
        assertNull(activeConnections.get("conn_sc-123"));
    }

    @Test
    @DisplayName("removeSidecar - 连接断开异常时不影响主流程")
    void removeSidecar_ConnectionException_ShouldNotFail() throws Exception {
        SidecarInstance sidecar = createTestSidecar("sc-123");
        when(sidecarInstanceMapper.selectById("sc-123")).thenReturn(sidecar);
        when(sidecarInstanceMapper.deleteById("sc-123")).thenReturn(1);

        Field activeConnectionsField = SidecarLifecycleService.class.getDeclaredField("activeConnections");
        activeConnectionsField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, HttpURLConnection> activeConnections =
                (ConcurrentHashMap<String, HttpURLConnection>) activeConnectionsField.get(sidecarLifecycleService);

        HttpURLConnection mockConn = mock(HttpURLConnection.class);
        doThrow(new RuntimeException("Connection error")).when(mockConn).disconnect();
        activeConnections.put("conn_sc-123", mockConn);

        boolean result = sidecarLifecycleService.removeSidecar("sc-123");

        assertTrue(result, "Should succeed even if disconnect fails");
        assertNull(activeConnections.get("conn_sc-123"));
    }

    @Test
    @DisplayName("closeQuietly - 资源关闭异常应被静默处理")
    void closeQuietly_ExceptionShouldBeSuppressed() throws Exception {
        Method closeQuietlyMethod = SidecarLifecycleService.class.getDeclaredMethod(
                "closeQuietly", AutoCloseable.class, String.class, String.class);
        closeQuietlyMethod.setAccessible(true);

        AutoCloseable failingResource = () -> {
            throw new Exception("Test exception");
        };

        assertDoesNotThrow(() -> closeQuietlyMethod.invoke(
                sidecarLifecycleService, failingResource, "sc-123", "TestResource"));
    }

    @Test
    @DisplayName("heartbeat - metrics为null时应优雅处理")
    void heartbeat_NullMetrics_ShouldNotFail() {
        SidecarInstance sidecar = createTestSidecar("sc-123");
        sidecar.setResources(null);
        when(sidecarInstanceMapper.selectById("sc-123")).thenReturn(sidecar);
        when(sidecarInstanceMapper.updateById(any(SidecarInstance.class))).thenReturn(1);

        boolean result = sidecarLifecycleService.heartbeat("sc-123", null);

        assertTrue(result);
    }

    @Test
    @DisplayName("heartbeat - metrics为null但已有resources时应保留原值")
    void heartbeat_NullMetricsWithExistingResources_ShouldKeepResources() {
        SidecarInstance sidecar = createTestSidecar("sc-123");
        Map<String, Object> originalResources = new HashMap<>();
        originalResources.put("cpu", "100m");
        sidecar.setResources(originalResources);

        when(sidecarInstanceMapper.selectById("sc-123")).thenReturn(sidecar);
        when(sidecarInstanceMapper.updateById(any(SidecarInstance.class))).thenReturn(1);

        sidecarLifecycleService.heartbeat("sc-123", null);

        verify(sidecarInstanceMapper).updateById(argThat(s ->
                s.getResources() != null && "100m".equals(s.getResources().get("cpu"))
        ));
    }

    @Test
    @DisplayName("heartbeat - metrics非null时应合并到resources")
    void heartbeat_WithMetrics_ShouldMergeResources() {
        SidecarInstance sidecar = createTestSidecar("sc-123");
        Map<String, Object> originalResources = new HashMap<>();
        originalResources.put("cpu", "100m");
        sidecar.setResources(originalResources);

        when(sidecarInstanceMapper.selectById("sc-123")).thenReturn(sidecar);
        when(sidecarInstanceMapper.updateById(any(SidecarInstance.class))).thenReturn(1);

        Map<String, Object> newMetrics = new HashMap<>();
        newMetrics.put("memory", "256Mi");
        newMetrics.put("connections", 42);

        sidecarLifecycleService.heartbeat("sc-123", newMetrics);

        verify(sidecarInstanceMapper).updateById(argThat(s ->
                s.getResources() != null &&
                        "100m".equals(s.getResources().get("cpu")) &&
                        "256Mi".equals(s.getResources().get("memory")) &&
                        Integer.valueOf(42).equals(s.getResources().get("connections"))
        ));
    }
}
