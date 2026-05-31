package com.meshcontrol.dns.service;

import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.dns.dto.DnsQueryRequest;
import com.meshcontrol.dns.dto.UpstreamRequest;
import com.meshcontrol.dns.entity.DnsUpstream;
import com.meshcontrol.dns.entity.DnsZone;
import com.meshcontrol.dns.mapper.DnsCacheMapper;
import com.meshcontrol.dns.mapper.DnsUpstreamMapper;
import com.meshcontrol.dns.mapper.DnsZoneMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DnsProxyService - 边界校验测试")
class DnsProxyServiceBoundaryTest {

    @Mock
    private DnsUpstreamMapper dnsUpstreamMapper;

    @Mock
    private DnsZoneMapper dnsZoneMapper;

    @Mock
    private DnsCacheMapper dnsCacheMapper;

    @InjectMocks
    private DnsProxyService dnsProxyService;

    @Test
    @DisplayName("addUpstream - 空值校验 - request为null")
    void addUpstream_NullRequest_ShouldThrowException() {
        assertThrows(BusinessException.class,
                () -> dnsProxyService.addUpstream(null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("addUpstream - name为空或空白")
    void addUpstream_NullOrEmptyName_ShouldThrowException(String name) {
        UpstreamRequest request = new UpstreamRequest();
        request.setName(name);
        request.setAddress("8.8.8.8");

        assertThrows(BusinessException.class, () -> dnsProxyService.addUpstream(request));
    }

    @Test
    @DisplayName("addUpstream - name超长")
    void addUpstream_LongName_ShouldThrowException() {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("a".repeat(65));
        request.setAddress("8.8.8.8");

        assertThrows(BusinessException.class, () -> dnsProxyService.addUpstream(request));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("addUpstream - address为空或空白")
    void addUpstream_NullOrEmptyAddress_ShouldThrowException(String address) {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("test-dns");
        request.setAddress(address);

        assertThrows(BusinessException.class, () -> dnsProxyService.addUpstream(request));
    }

    @Test
    @DisplayName("addUpstream - address超长")
    void addUpstream_LongAddress_ShouldThrowException() {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("test-dns");
        request.setAddress("a".repeat(256));

        assertThrows(BusinessException.class, () -> dnsProxyService.addUpstream(request));
    }

    @Test
    @DisplayName("addUpstream - 无效IP地址")
    void addUpstream_InvalidIpAddress_ShouldThrowException() {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("test-dns");
        request.setAddress("999.999.999.999");

        assertThrows(BusinessException.class, () -> dnsProxyService.addUpstream(request));
    }

    @Test
    @DisplayName("addUpstream - 无效域名")
    void addUpstream_InvalidDomain_ShouldThrowException() {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("test-dns");
        request.setAddress("-invalid.com");

        assertThrows(BusinessException.class, () -> dnsProxyService.addUpstream(request));
    }

    @Test
    @DisplayName("addUpstream - 有效IP地址应通过")
    void addUpstream_ValidIpAddress_ShouldSucceed() {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("test-dns");
        request.setAddress("8.8.8.8");

        when(dnsUpstreamMapper.insert(any(DnsUpstream.class))).thenReturn(1);

        assertDoesNotThrow(() -> dnsProxyService.addUpstream(request));
    }

    @Test
    @DisplayName("addUpstream - 有效域名应通过")
    void addUpstream_ValidDomain_ShouldSucceed() {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("test-dns");
        request.setAddress("dns.google.com");

        when(dnsUpstreamMapper.insert(any(DnsUpstream.class))).thenReturn(1);

        assertDoesNotThrow(() -> dnsProxyService.addUpstream(request));
    }

    @Test
    @DisplayName("addUpstream - 无效协议应抛出异常")
    void addUpstream_InvalidProtocol_ShouldThrowException() {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("test-dns");
        request.setAddress("8.8.8.8");
        request.setProtocol("invalid");

        assertThrows(BusinessException.class, () -> dnsProxyService.addUpstream(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"udp", "tcp", "tls", "UDP", "TCP", "TLS"})
    @DisplayName("addUpstream - 有效协议应通过")
    void addUpstream_ValidProtocol_ShouldSucceed(String protocol) {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("test-dns");
        request.setAddress("8.8.8.8");
        request.setProtocol(protocol);

        when(dnsUpstreamMapper.insert(any(DnsUpstream.class))).thenReturn(1);

        assertDoesNotThrow(() -> dnsProxyService.addUpstream(request));
    }

    @Test
    @DisplayName("normalizePort - 边界值测试")
    void normalizePort_BoundaryValues_ShouldNormalize() throws Exception {
        Method normalizePortMethod = DnsProxyService.class.getDeclaredMethod(
                "normalizePort", Integer.class);
        normalizePortMethod.setAccessible(true);

        assertEquals(53, normalizePortMethod.invoke(dnsProxyService, (Integer) null));
        assertEquals(53, normalizePortMethod.invoke(dnsProxyService, 0));
        assertEquals(53, normalizePortMethod.invoke(dnsProxyService, -1));
        assertEquals(53, normalizePortMethod.invoke(dnsProxyService, 65536));
        assertEquals(1, normalizePortMethod.invoke(dnsProxyService, 1));
        assertEquals(65535, normalizePortMethod.invoke(dnsProxyService, 65535));
        assertEquals(5353, normalizePortMethod.invoke(dnsProxyService, 5353));
    }

    @Test
    @DisplayName("normalizeTimeout - 边界值测试")
    void normalizeTimeout_BoundaryValues_ShouldNormalize() throws Exception {
        Method normalizeTimeoutMethod = DnsProxyService.class.getDeclaredMethod(
                "normalizeTimeout", Integer.class);
        normalizeTimeoutMethod.setAccessible(true);

        assertEquals(5000, normalizeTimeoutMethod.invoke(dnsProxyService, (Integer) null));
        assertEquals(100, normalizeTimeoutMethod.invoke(dnsProxyService, 0));
        assertEquals(100, normalizeTimeoutMethod.invoke(dnsProxyService, 50));
        assertEquals(60000, normalizeTimeoutMethod.invoke(dnsProxyService, 60001));
        assertEquals(100, normalizeTimeoutMethod.invoke(dnsProxyService, 100));
        assertEquals(60000, normalizeTimeoutMethod.invoke(dnsProxyService, 60000));
        assertEquals(5000, normalizeTimeoutMethod.invoke(dnsProxyService, 5000));
    }

    @Test
    @DisplayName("normalizeTtl - 边界值测试")
    void normalizeTtl_BoundaryValues_ShouldNormalize() throws Exception {
        Method normalizeTtlMethod = DnsProxyService.class.getDeclaredMethod(
                "normalizeTtl", Integer.class);
        normalizeTtlMethod.setAccessible(true);

        assertEquals(300, normalizeTtlMethod.invoke(dnsProxyService, (Integer) null));
        assertEquals(1, normalizeTtlMethod.invoke(dnsProxyService, 0));
        assertEquals(1, normalizeTtlMethod.invoke(dnsProxyService, -1));
        assertEquals(86400, normalizeTtlMethod.invoke(dnsProxyService, 86401));
        assertEquals(1, normalizeTtlMethod.invoke(dnsProxyService, 1));
        assertEquals(86400, normalizeTtlMethod.invoke(dnsProxyService, 86400));
        assertEquals(300, normalizeTtlMethod.invoke(dnsProxyService, 300));
    }

    @Test
    @DisplayName("resolve - 空值校验")
    void resolve_NullRequest_ShouldThrowException() {
        assertThrows(BusinessException.class, () -> dnsProxyService.resolve(null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("resolve - domain为空或空白")
    void resolve_NullOrEmptyDomain_ShouldThrowException(String domain) {
        DnsQueryRequest request = new DnsQueryRequest();
        request.setDomain(domain);

        assertThrows(BusinessException.class, () -> dnsProxyService.resolve(request));
    }

    @Test
    @DisplayName("resolve - domain超长")
    void resolve_LongDomain_ShouldThrowException() {
        DnsQueryRequest request = new DnsQueryRequest();
        request.setDomain("a".repeat(254));

        assertThrows(BusinessException.class, () -> dnsProxyService.resolve(request));
    }

    @Test
    @DisplayName("resolve - 域名标签超长")
    void resolve_LongLabelInDomain_ShouldThrowException() {
        String longLabel = "a".repeat(64);
        DnsQueryRequest request = new DnsQueryRequest();
        request.setDomain(longLabel + ".com");

        assertThrows(BusinessException.class, () -> dnsProxyService.resolve(request));
    }

    @Test
    @DisplayName("normalizeDomain - 域名标准化测试")
    void normalizeDomain_ShouldNormalize() throws Exception {
        Method normalizeDomainMethod = DnsProxyService.class.getDeclaredMethod(
                "normalizeDomain", String.class);
        normalizeDomainMethod.setAccessible(true);

        assertEquals("example.com", normalizeDomainMethod.invoke(dnsProxyService, "EXAMPLE.COM"));
        assertEquals("example.com", normalizeDomainMethod.invoke(dnsProxyService, "  example.com  "));
        assertEquals("example.com", normalizeDomainMethod.invoke(dnsProxyService, "example.com."));
    }

    @Test
    @DisplayName("normalizeQueryType - 类型标准化测试")
    void normalizeQueryType_ShouldNormalize() throws Exception {
        Method normalizeQueryTypeMethod = DnsProxyService.class.getDeclaredMethod(
                "normalizeQueryType", String.class);
        normalizeQueryTypeMethod.setAccessible(true);

        assertEquals("A", normalizeQueryTypeMethod.invoke(dnsProxyService, (String) null));
        assertEquals("A", normalizeQueryTypeMethod.invoke(dnsProxyService, ""));
        assertEquals("A", normalizeQueryTypeMethod.invoke(dnsProxyService, "   "));
        assertEquals("A", normalizeQueryTypeMethod.invoke(dnsProxyService, "a"));
        assertEquals("AAAA", normalizeQueryTypeMethod.invoke(dnsProxyService, "aaaa"));
        assertEquals("MX", normalizeQueryTypeMethod.invoke(dnsProxyService, "mx"));
        assertEquals("A", normalizeQueryTypeMethod.invoke(dnsProxyService, "INVALID"));
    }

    @Test
    @DisplayName("resolve - 无效上游服务器应抛出异常")
    void resolve_NoUpstreams_ShouldThrowException() {
        DnsQueryRequest request = new DnsQueryRequest();
        request.setDomain("example.com");

        when(dnsCacheMapper.findValidCache(any(), any())).thenReturn(null);
        when(dnsZoneMapper.findByDomain(any())).thenReturn(null);
        when(dnsZoneMapper.findBestMatch(any())).thenReturn(null);
        when(dnsUpstreamMapper.findAllEnabled()).thenReturn(Collections.emptyList());

        assertThrows(BusinessException.class, () -> dnsProxyService.resolve(request));
    }

    @Test
    @DisplayName("validateResolveInput - 完整校验")
    void validateResolveInput_Validation_ShouldThrowException() throws Exception {
        Method validateMethod = DnsProxyService.class.getDeclaredMethod(
                "validateResolveInput", String.class, String.class, DnsUpstream.class);
        validateMethod.setAccessible(true);

        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(dnsProxyService, null, "A", new DnsUpstream()));
        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(dnsProxyService, "", "A", new DnsUpstream()));
        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(dnsProxyService, "a".repeat(254), "A", new DnsUpstream()));
        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(dnsProxyService, "example.com", null, new DnsUpstream()));
        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(dnsProxyService, "example.com", "", new DnsUpstream()));
        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(dnsProxyService, "example.com", "A", null));

        DnsUpstream upstream = new DnsUpstream();
        upstream.setAddress("");
        assertThrows(BusinessException.class,
                () -> validateMethod.invoke(dnsProxyService, "example.com", "A", upstream));
    }

    @Test
    @DisplayName("addUpstream - null值使用默认值")
    void addUpstream_NullOptionalFields_ShouldUseDefaults() {
        UpstreamRequest request = new UpstreamRequest();
        request.setName("test-dns");
        request.setAddress("8.8.8.8");
        request.setPort(null);
        request.setProtocol(null);
        request.setTimeoutMs(null);
        request.setPriority(null);
        request.setEnabled(null);
        request.setHealthCheckEnabled(null);

        when(dnsUpstreamMapper.insert(any(DnsUpstream.class))).thenAnswer(invocation -> {
            DnsUpstream upstream = invocation.getArgument(0);
            assertEquals(53, upstream.getPort());
            assertEquals("udp", upstream.getProtocol());
            assertEquals(5000, upstream.getTimeoutMs());
            assertEquals(0, upstream.getPriority());
            assertTrue(upstream.getEnabled());
            assertTrue(upstream.getHealthCheckEnabled());
            return 1;
        });

        dnsProxyService.addUpstream(request);
    }

    @Test
    @DisplayName("resolve - type为null时默认使用A记录")
    void resolve_NullType_ShouldDefaultToA() {
        DnsQueryRequest request = new DnsQueryRequest();
        request.setDomain("example.com");
        request.setType(null);

        DnsUpstream upstream = new DnsUpstream();
        upstream.setUpstreamId("up-1");
        upstream.setName("test-dns");
        upstream.setAddress("8.8.8.8");
        upstream.setPort(53);

        when(dnsCacheMapper.findValidCache(any(), any())).thenReturn(null);
        when(dnsZoneMapper.findByDomain(any())).thenReturn(null);
        when(dnsZoneMapper.findBestMatch(any())).thenReturn(null);
        when(dnsUpstreamMapper.findAllEnabled()).thenReturn(List.of(upstream));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> dnsProxyService.resolve(request));
        assertTrue(exception.getMessage().contains("DNS resolution failed") ||
                exception.getMessage().contains("No available DNS upstreams") ||
                exception.getMessage().contains("Invalid upstream address"));
    }
}
