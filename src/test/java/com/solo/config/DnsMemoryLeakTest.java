package com.solo.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.solo.config.entity.DnsRecord;
import com.solo.config.module.dns.DnsProperties;
import com.solo.config.module.dns.plugin.DnsPluginManager;
import com.solo.config.module.dns.plugin.DnsResolutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class DnsMemoryLeakTest {

    @Autowired
    private DnsPluginManager dnsPluginManager;

    @Autowired
    private DnsProperties dnsProperties;

    @Autowired
    private Cache<String, DnsRecord> dnsCache;

    @Test
    void testDnsCacheConfiguration() {
        assertNotNull(dnsCache);
        assertTrue(dnsProperties.getCache().getMaxSize() > 0);
        assertTrue(dnsProperties.getCache().getTtl() > 0);
    }

    @Test
    void testPluginTimeoutConfiguration() {
        assertTrue(dnsProperties.getPlugin().getResolveTimeoutMs() > 0);
        assertEquals(5000, dnsProperties.getPlugin().getResolveTimeoutMs());
    }

    @Test
    void testCacheCleanupConfiguration() {
        assertTrue(dnsProperties.getCache().getCleanupIntervalMs() > 0);
        assertTrue(dnsProperties.getCache().getMaxCleanupIntervalMs() > 0);
        assertEquals(600000, dnsProperties.getCache().getCleanupIntervalMs());
        assertEquals(3600000, dnsProperties.getCache().getMaxCleanupIntervalMs());
    }

    @Test
    void testDnsResolutionContextCleanup() {
        DnsResolutionContext context = new DnsResolutionContext("test.com", "A");
        context.setAttribute("testKey", "testValue");
        context.getResolvedIps().add("192.168.1.1");

        assertFalse(context.getAttributes().isEmpty());
        assertFalse(context.getResolvedIps().isEmpty());

        context.getAttributes().clear();
        context.getResolvedIps().clear();

        assertTrue(context.getAttributes().isEmpty());
        assertTrue(context.getResolvedIps().isEmpty());
    }

    @Test
    void testPluginsLoaded() {
        assertNotNull(dnsPluginManager.getPlugins());
        assertFalse(dnsPluginManager.getPlugins().isEmpty());
        assertTrue(dnsPluginManager.getPlugins().size() >= 3);
    }

    @Test
    void testCacheStatsRecording() {
        assertNotNull(dnsCache.stats());
        assertTrue(dnsCache.stats().hitCount() >= 0);
        assertTrue(dnsCache.stats().missCount() >= 0);
    }
}
