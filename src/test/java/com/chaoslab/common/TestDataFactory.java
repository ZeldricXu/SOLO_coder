package com.chaoslab.common;

import com.chaoslab.entity.*;
import com.chaoslab.modules.sidecar.dto.ConfigUpdateRequest;
import com.chaoslab.modules.sidecar.dto.InjectionPolicyCreateRequest;
import com.chaoslab.modules.sidecar.dto.ResourceLimitUpdateRequest;
import com.chaoslab.modules.sidecar.dto.*;
import com.chaoslab.modules.mtls.dto.CertificateIssueRequest;
import com.chaoslab.modules.mtls.dto.RevocationRequest;
import com.chaoslab.modules.mtls.dto.RotationPolicyCreateRequest;
import com.chaoslab.modules.dns.dto.DnsResolveRequest;
import com.chaoslab.modules.dns.dto.ResolutionPolicyCreateRequest;
import com.chaoslab.modules.dns.dto.UpstreamCreateRequest;
import com.chaoslab.modules.dns.dto.AsyncResolveRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

public class TestDataFactory {

    private static final Random RANDOM = new Random();
    private static final String[] NAMESPACES = {"production", "staging", "development", "testing"};
    private static final String[] FAULT_TYPES = {"network_latency", "cpu_stress", "process_kill"};

    public static String randomId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String randomNamespace() {
        return NAMESPACES[RANDOM.nextInt(NAMESPACES.length)];
    }

    // ==================== Sidecar Module Test Data ====================

    public static InjectionPolicyCreateRequest createInjectionPolicyRequest() {
        InjectionPolicyCreateRequest request = new InjectionPolicyCreateRequest();
        request.setName("test-sidecar-policy-" + RANDOM.nextInt(1000));
        request.setNamespace(randomNamespace());
        request.setSidecarImage("chaoslab/sidecar-proxy:v" + RANDOM.nextInt(10));
        request.setInjectionMode("automatic");
        request.setEnabled(true);
        request.setSelector(Map.of(
                "app", "test-app",
                "environment", request.getNamespace()
        ));
        request.setResources(Map.of(
                "cpuLimit", "500m",
                "memoryLimit", "256Mi",
                "cpuRequest", "100m",
                "memoryRequest", "128Mi"
        ));
        return request;
    }

    public static SidecarInjectionPolicy createSidecarInjectionPolicy() {
        SidecarInjectionPolicy policy = new SidecarInjectionPolicy();
        policy.setId(RANDOM.nextLong(1000, 9999));
        policy.setPolicyId(randomId("pol"));
        policy.setName("test-policy-" + RANDOM.nextInt(1000));
        policy.setNamespace(randomNamespace());
        policy.setSidecarImage("chaoslab/sidecar-proxy:v1.0.0");
        policy.setInjectionMode("automatic");
        policy.setEnabled(true);
        policy.setSelector(Map.of("app", "test"));
        policy.setResources(defaultResources());
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        policy.setVersion(0);
        policy.setDeleted(0);
        return policy;
    }

    public static SidecarInstance createSidecarInstance(String policyId) {
        SidecarInstance instance = new SidecarInstance();
        instance.setId(RANDOM.nextLong(1000, 9999));
        instance.setInstanceId(randomId("si"));
        instance.setPolicyId(policyId);
        instance.setTargetPod("test-pod-" + RANDOM.nextInt(100));
        instance.setNamespace(randomNamespace());
        instance.setStatus("running");
        instance.setConfigHash(UUID.randomUUID().toString().substring(0, 32));
        instance.setLastHeartbeat(LocalDateTime.now());
        instance.setCreatedAt(LocalDateTime.now());
        instance.setUpdatedAt(LocalDateTime.now());
        instance.setVersion(0);
        instance.setDeleted(0);
        return instance;
    }

    public static SidecarConfig createSidecarConfig(String instanceId) {
        SidecarConfig config = new SidecarConfig();
        config.setId(RANDOM.nextLong(1000, 9999));
        config.setConfigId(randomId("sc"));
        config.setInstanceId(instanceId);
        config.setConfigData(Map.of(
                "logLevel", "INFO",
                "timeout", 30,
                "retryCount", 3
        ));
        config.setVersion(1);
        config.setApplied(true);
        config.setAppliedAt(LocalDateTime.now());
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        config.setVersionLock(0);
        config.setDeleted(0);
        return config;
    }

    public static ConfigUpdateRequest createConfigUpdateRequest(String instanceId) {
        ConfigUpdateRequest request = new ConfigUpdateRequest();
        request.setInstanceId(instanceId);
        request.setConfigData(Map.of(
                "logLevel", "DEBUG",
                "timeout", 60,
                "newFeature", true
        ));
        return request;
    }

    public static ResourceLimitUpdateRequest createResourceLimitUpdateRequest(String instanceId) {
        ResourceLimitUpdateRequest request = new ResourceLimitUpdateRequest();
        request.setInstanceId(instanceId);
        request.setCpuLimit(new BigDecimal("1000"));
        request.setMemoryLimit(new BigDecimal("512"));
        request.setCpuRequest(new BigDecimal("200"));
        request.setMemoryRequest(new BigDecimal("256"));
        return request;
    }

    // ==================== mTLS Module Test Data ====================

    public static RotationPolicyCreateRequest createRotationPolicyRequest() {
        RotationPolicyCreateRequest request = new RotationPolicyCreateRequest();
        request.setName("test-rotation-policy-" + RANDOM.nextInt(1000));
        request.setValidityDays(365);
        request.setRotationDays(30);
        request.setAutoRotate(true);
        request.setKeyAlgorithm("RSA");
        request.setKeySize(2048);
        request.setSignatureAlgorithm("SHA256withRSA");
        request.setEnabled(true);
        return request;
    }

    public static MtlsRotationPolicy createMtlsRotationPolicy() {
        MtlsRotationPolicy policy = new MtlsRotationPolicy();
        policy.setId(RANDOM.nextLong(1000, 9999));
        policy.setPolicyId(randomId("rp"));
        policy.setName("test-policy-" + RANDOM.nextInt(1000));
        policy.setValidityDays(365);
        policy.setRotationDays(30);
        policy.setAutoRotate(true);
        policy.setKeyAlgorithm("RSA");
        policy.setKeySize(2048);
        policy.setSignatureAlgorithm("SHA256withRSA");
        policy.setEnabled(true);
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        policy.setVersion(0);
        policy.setDeleted(0);
        return policy;
    }

    public static MtlsRotationPolicy createRotationPolicy() {
        return createMtlsRotationPolicy();
    }

    public static CertificateIssueRequest createCertificateIssueRequest() {
        CertificateIssueRequest request = new CertificateIssueRequest();
        request.setCommonName("test-service-" + RANDOM.nextInt(1000) + ".chaoslab.local");
        request.setSubjectAlternativeNames(List.of(
                "test-service.chaoslab.local",
                "*.chaoslab.local"
        ));
        request.setOrganization("ChaosLab Test Org");
        request.setOrganizationalUnit("Engineering");
        request.setCountry("CN");
        request.setValidityDays(365);
        return request;
    }

    public static MtlsCertificate createCertificate(String rotationPolicyId) {
        MtlsCertificate cert = new MtlsCertificate();
        cert.setId(RANDOM.nextLong(1000, 9999));
        cert.setCertId(randomId("cert"));
        cert.setCommonName("test-service.chaoslab.local");
        cert.setSerialNumber(UUID.randomUUID().toString().substring(0, 16));
        cert.setCertificatePem("-----BEGIN CERTIFICATE-----\nMIID...\n-----END CERTIFICATE-----");
        cert.setPrivateKeyPem("-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----");
        cert.setIssuer("CN=ChaosLab Test CA, O=ChaosLab Test Org");
        cert.setNotBefore(LocalDateTime.now());
        cert.setNotAfter(LocalDateTime.now().plusDays(365));
        cert.setStatus("active");
        cert.setRotationPolicyId(rotationPolicyId);
        cert.setCreatedAt(LocalDateTime.now());
        cert.setUpdatedAt(LocalDateTime.now());
        cert.setVersion(0);
        cert.setDeleted(0);
        return cert;
    }

    public static MtlsCertificate createExpiringCertificate(String rotationPolicyId) {
        MtlsCertificate cert = createCertificate(rotationPolicyId);
        cert.setNotAfter(LocalDateTime.now().plusDays(10));
        return cert;
    }

    public static RevocationRequest createRevocationRequest(String certId) {
        RevocationRequest request = new RevocationRequest();
        request.setCertId(certId);
        request.setReason("Key compromise");
        request.setRevokedBy("test-admin@chaoslab.local");
        return request;
    }

    public static MtlsRevocationList createRevocationList(String certId, String serialNumber) {
        MtlsRevocationList revocation = new MtlsRevocationList();
        revocation.setId(RANDOM.nextLong(1000, 9999));
        revocation.setRevocationId(randomId("rev"));
        revocation.setCertId(certId);
        revocation.setSerialNumber(serialNumber);
        revocation.setReason("Key compromise");
        revocation.setRevokedAt(LocalDateTime.now());
        revocation.setRevokedBy("test-admin@chaoslab.local");
        revocation.setCrlNumber(RANDOM.nextInt(1000));
        revocation.setCreatedAt(LocalDateTime.now());
        revocation.setUpdatedAt(LocalDateTime.now());
        revocation.setVersion(0);
        revocation.setDeleted(0);
        return revocation;
    }

    // ==================== DNS Module Test Data ====================

    public static UpstreamCreateRequest createUpstreamRequest() {
        UpstreamCreateRequest request = new UpstreamCreateRequest();
        request.setName("test-upstream-" + RANDOM.nextInt(1000));
        request.setAddress(RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + "." +
                RANDOM.nextInt(256) + "." + RANDOM.nextInt(256) + ":53");
        request.setProtocol("udp");
        request.setTimeoutMs(5000);
        request.setPriority(RANDOM.nextInt(100) + 1);
        request.setHealthCheckEnabled(true);
        return request;
    }

    public static UpstreamCreateRequest createUpstreamCreateRequest() {
        return createUpstreamRequest();
    }

    public static DnsUpstream createDnsUpstream() {
        DnsUpstream upstream = new DnsUpstream();
        upstream.setId(RANDOM.nextLong(1000, 9999));
        upstream.setUpstreamId(randomId("du"));
        upstream.setName("google-dns-" + RANDOM.nextInt(100));
        upstream.setAddress("8.8.8.8:53");
        upstream.setProtocol("udp");
        upstream.setTimeoutMs(5000);
        upstream.setPriority(100);
        upstream.setHealthCheckEnabled(true);
        upstream.setStatus("healthy");
        upstream.setCreatedAt(LocalDateTime.now());
        upstream.setUpdatedAt(LocalDateTime.now());
        upstream.setVersion(0);
        upstream.setDeleted(0);
        return upstream;
    }

    public static DnsUpstream createUnhealthyDnsUpstream() {
        DnsUpstream upstream = createDnsUpstream();
        upstream.setStatus("unhealthy");
        return upstream;
    }

    public static ResolutionPolicyCreateRequest createResolutionPolicyRequest(List<String> upstreamIds) {
        ResolutionPolicyCreateRequest request = new ResolutionPolicyCreateRequest();
        request.setName("test-policy-" + RANDOM.nextInt(1000));
        request.setDomainPattern("*.chaoslab.local");
        request.setStrategy("round_robin");
        request.setUpstreamIds(upstreamIds);
        request.setCacheTtl(300);
        request.setEnabled(true);
        return request;
    }

    public static ResolutionPolicyCreateRequest createResolutionPolicyCreateRequest() {
        return createResolutionPolicyRequest(Collections.emptyList());
    }

    public static DnsResolutionPolicy createDnsResolutionPolicy(List<String> upstreamIds) {
        DnsResolutionPolicy policy = new DnsResolutionPolicy();
        policy.setId(RANDOM.nextLong(1000, 9999));
        policy.setPolicyId(randomId("dp"));
        policy.setName("chaoslab-local-policy");
        policy.setDomainPattern("*.chaoslab.local");
        policy.setStrategy("round_robin");
        policy.setUpstreamIds(upstreamIds);
        policy.setCacheTtl(300);
        policy.setEnabled(true);
        policy.setCreatedAt(LocalDateTime.now());
        policy.setUpdatedAt(LocalDateTime.now());
        policy.setVersion(0);
        policy.setDeleted(0);
        return policy;
    }

    public static DnsResolutionPolicy createDnsResolutionPolicy() {
        return createDnsResolutionPolicy(Collections.emptyList());
    }

    public static DnsResolveRequest createDnsResolveRequest(String domain) {
        DnsResolveRequest request = new DnsResolveRequest();
        request.setDomain(domain);
        request.setQueryType("A");
        request.setForceRefresh(false);
        return request;
    }

    public static DnsResolveRequest createDnsResolveRequest() {
        return createDnsResolveRequest("test-service-" + RANDOM.nextInt(1000) + ".chaoslab.local");
    }

    public static DnsCache createDnsCache(String domain, String queryType) {
        DnsCache cache = new DnsCache();
        cache.setId(RANDOM.nextLong(1000, 9999));
        cache.setCacheId(randomId("dc"));
        cache.setQueryKey(domain + ":" + queryType);
        cache.setQueryType(queryType);
        cache.setResponseData(Map.of(
                "answers", List.of("192.168.1." + RANDOM.nextInt(255)),
                "ttl", 300
        ));
        cache.setTtl(300);
        cache.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        cache.setHitCount(0);
        cache.setCreatedAt(LocalDateTime.now());
        cache.setUpdatedAt(LocalDateTime.now());
        cache.setDeleted(0);
        return cache;
    }

    // ==================== Sidecar Dynamic Config Test Data ====================

    public static DynamicConfigCreateRequest createDynamicConfigCreateRequest() {
        DynamicConfigCreateRequest request = new DynamicConfigCreateRequest();
        request.setConfigKey("sidecar.resource.cpu.limit." + RANDOM.nextInt(1000));
        request.setConfigName("CPU Limit " + RANDOM.nextInt(1000));
        request.setConfigType("resource");
        request.setDescription("Sidecar CPU resource limit");
        request.setConfigValue(Map.of("value", "500m"));
        request.setDefaultValue("500m");
        request.setHotReloadable(true);
        request.setScope(randomNamespace());
        return request;
    }

    public static DynamicConfig createDynamicConfig() {
        DynamicConfig config = new DynamicConfig();
        config.setId(RANDOM.nextLong(1000, 9999));
        config.setConfigId(randomId("dc"));
        config.setConfigKey("sidecar.resource.cpu.limit." + RANDOM.nextInt(1000));
        config.setConfigName("CPU Limit");
        config.setConfigType("resource");
        config.setDescription("Sidecar CPU resource limit");
        config.setConfigValue(Map.of("value", "500m"));
        config.setDefaultValue("500m");
        config.setEnabled(true);
        config.setHotReloadable(true);
        config.setScope("global");
        config.setLastModifiedBy("test-admin");
        config.setLastModifiedAt(LocalDateTime.now());
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        config.setVersion(1);
        config.setDeleted(0);
        return config;
    }

    public static DynamicConfigUpdateRequest createDynamicConfigUpdateRequest(String configId) {
        DynamicConfigUpdateRequest request = new DynamicConfigUpdateRequest();
        request.setConfigId(configId);
        request.setConfigValue(Map.of("value", "1000m"));
        request.setChangedBy("test-admin");
        request.setChangeReason("Performance optimization");
        return request;
    }

    public static ConfigTemplateCreateRequest createConfigTemplateCreateRequest() {
        ConfigTemplateCreateRequest request = new ConfigTemplateCreateRequest();
        request.setTemplateName("Production Template " + RANDOM.nextInt(1000));
        request.setTemplateType("sidecar");
        request.setScenario(randomNamespace());
        request.setDescription("Production environment sidecar template");
        request.setConfigData(Map.of(
                "logLevel", "WARN",
                "timeout", 30,
                "retryCount", 5
        ));
        request.setResourceLimits(Map.of(
                "cpuLimit", "1000m",
                "memoryLimit", "512Mi",
                "cpuRequest", "200m",
                "memoryRequest", "256Mi"
        ));
        request.setPriority(RANDOM.nextInt(100));
        return request;
    }

    public static ConfigTemplate createConfigTemplate() {
        ConfigTemplate template = new ConfigTemplate();
        template.setId(RANDOM.nextLong(1000, 9999));
        template.setTemplateId(randomId("ct"));
        template.setTemplateName("Production Template");
        template.setTemplateType("sidecar");
        template.setScenario(randomNamespace());
        template.setDescription("Production environment template");
        template.setConfigData(Map.of(
                "logLevel", "WARN",
                "timeout", 30
        ));
        template.setResourceLimits(Map.of(
                "cpuLimit", "1000m",
                "memoryLimit", "512Mi"
        ));
        template.setEnabled(true);
        template.setPriority(1);
        template.setCreatedBy("test-admin");
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());
        template.setVersion(1);
        template.setDeleted(0);
        return template;
    }

    public static ConfigApplyRequest createConfigApplyRequest(String instanceId, String templateId) {
        ConfigApplyRequest request = new ConfigApplyRequest();
        request.setInstanceId(instanceId);
        request.setTemplateId(templateId);
        request.setAppliedBy("test-admin");
        request.setReason("Configuration update");
        return request;
    }

    // ==================== DNS Async Test Data ====================

    public static AsyncResolveRequest createAsyncResolveRequest() {
        AsyncResolveRequest request = new AsyncResolveRequest();
        request.setDomain("test-" + RANDOM.nextInt(1000) + ".example.com");
        request.setQueryType("A");
        request.setPriority("normal");
        request.setCallbackType("event");
        request.setEventName("DNS_RESOLVE_COMPLETED");
        request.setMaxRetries(3);
        request.setRequestedBy("test-user");
        request.setContext(Map.of("traceId", UUID.randomUUID().toString()));
        return request;
    }

    public static DnsAsyncTask createDnsAsyncTask() {
        DnsAsyncTask task = new DnsAsyncTask();
        task.setId(RANDOM.nextLong(1000, 9999));
        task.setTaskId(randomId("dat"));
        task.setDomain("test-" + RANDOM.nextInt(1000) + ".example.com");
        task.setQueryType("A");
        task.setStatus("PENDING");
        task.setPriority("normal");
        task.setCallbackType("event");
        task.setEventName("DNS_RESOLVE_COMPLETED");
        task.setMaxRetries(3);
        task.setRetryCount(0);
        task.setSubmittedAt(LocalDateTime.now());
        task.setRequestedBy("test-user");
        task.setContext(Map.of("traceId", UUID.randomUUID().toString()));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        task.setVersion(0);
        task.setDeleted(0);
        return task;
    }

    public static DnsAsyncTask createCompletedDnsAsyncTask() {
        DnsAsyncTask task = createDnsAsyncTask();
        task.setStatus("COMPLETED");
        task.setStartedAt(LocalDateTime.now().minusSeconds(5));
        task.setCompletedAt(LocalDateTime.now());
        task.setDurationMs(150L);
        task.setResult(Map.of(
                "status", "NOERROR",
                "records", List.of(Map.of(
                        "value", "192.168.1." + RANDOM.nextInt(255),
                        "ttl", 300
                ))
        ));
        return task;
    }

    public static DnsAsyncTask createFailedDnsAsyncTask() {
        DnsAsyncTask task = createDnsAsyncTask();
        task.setStatus("FAILED");
        task.setStartedAt(LocalDateTime.now().minusSeconds(10));
        task.setCompletedAt(LocalDateTime.now());
        task.setDurationMs(5000L);
        task.setRetryCount(3);
        task.setErrorMessage("DNS resolution timeout after 5000ms");
        return task;
    }

    // ==================== Common Test Data ====================

    public static Map<String, Object> defaultResources() {
        return Map.of(
                "cpuLimit", "500m",
                "memoryLimit", "256Mi",
                "cpuRequest", "100m",
                "memoryRequest", "128Mi"
        );
    }

    public static List<String> generateUpstreamIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(randomId("du"));
        }
        return ids;
    }

    public static <T> List<T> generateList(int count, java.util.function.Supplier<T> supplier) {
        List<T> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(supplier.get());
        }
        return list;
    }
}
