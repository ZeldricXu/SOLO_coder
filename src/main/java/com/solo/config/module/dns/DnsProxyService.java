package com.solo.config.module.dns;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.solo.config.entity.DnsRecord;
import com.solo.config.mapper.DnsRecordMapper;
import com.solo.config.module.dns.plugin.DnsPluginManager;
import com.solo.config.module.dns.plugin.DnsResolverPlugin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DnsProxyService {

    private final DnsProperties properties;
    private final DnsRecordMapper dnsRecordMapper;
    private final Cache<String, DnsRecord> dnsCache;
    private final DnsPluginManager pluginManager;

    public Mono<List<String>> resolve(String domain, String recordType) {
        return Mono.fromCallable(() -> pluginManager.resolve(domain, recordType));
    }

    public Mono<Map<String, Object>> resolveWithDetails(String domain, String recordType) {
        return Mono.fromCallable(() -> {
            long start = System.currentTimeMillis();
            List<String> results = pluginManager.resolve(domain, recordType);
            long duration = System.currentTimeMillis() - start;

            return Map.of(
                    "domain", domain,
                    "recordType", recordType,
                    "ips", results,
                    "resolved", !results.isEmpty(),
                    "resolveTimeMs", duration,
                    "plugins", pluginManager.getPlugins().stream()
                            .map(DnsResolverPlugin::getName)
                            .toList()
            );
        });
    }

    public Mono<Void> refreshCache() {
        return Mono.fromRunnable(() -> {
            dnsCache.invalidateAll();
            log.info("DNS cache cleared");
        });
    }

    @Scheduled(fixedRateString = "${dns.cache.cleanup-interval-ms:600000}")
    public void cleanupExpiredRecords() {
        LocalDateTime now = LocalDateTime.now();
        int deleted = dnsRecordMapper.delete(
                new QueryWrapper<DnsRecord>().lt("expires_at", now)
        );
        if (deleted > 0) {
            log.info("Cleaned up {} expired DNS records", deleted);
        }
    }

    @Scheduled(fixedRateString = "${dns.cache.max-cleanup-interval-ms:3600000}")
    public void cleanupOldestRecordsIfExceedLimit() {
        int maxRecords = properties.getCache().getMaxSize();
        Long count = dnsRecordMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DnsRecord>()
        );

        if (count != null && count > maxRecords * 1.5) {
            int deleteCount = (int) (count - maxRecords);
            List<Long> idsToDelete = dnsRecordMapper.selectList(
                    new QueryWrapper<DnsRecord>()
                            .orderByAsc("cached_at")
                            .last("LIMIT " + deleteCount)
            ).stream().map(DnsRecord::getId).toList();

            if (!idsToDelete.isEmpty()) {
                dnsRecordMapper.deleteBatchIds(idsToDelete);
                log.info("Cleaned up {} oldest DNS records to stay within limit: {}", idsToDelete.size(), maxRecords);
            }
        }

        logCacheStats();
    }

    private void logCacheStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats stats = dnsCache.stats();
        log.debug("DNS cache stats - size: {}, hitCount: {}, missCount: {}, hitRate: {:.2f}%",
                dnsCache.estimatedSize(),
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate() * 100);
    }

    public Mono<List<DnsRecord>> listCachedRecords() {
        return Mono.fromCallable(() ->
                dnsRecordMapper.selectList(
                        new QueryWrapper<DnsRecord>()
                                .gt("expires_at", LocalDateTime.now())
                                .orderByDesc("cached_at")
                                .last("LIMIT 100")
                )
        );
    }
}
