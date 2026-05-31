package com.solo.config.module.dns.plugin;

import com.github.benmanes.caffeine.cache.Cache;
import com.solo.config.entity.DnsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheDnsResolverPlugin implements DnsResolverPlugin {

    private final Cache<String, DnsRecord> dnsCache;

    @Override
    public String getName() {
        return "cache";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public List<String> resolve(String domain, String recordType, DnsResolutionContext context) {
        String cacheKey = domain + ":" + recordType;
        DnsRecord cached = dnsCache.getIfPresent(cacheKey);

        if (cached != null && cached.getExpiresAt().isAfter(LocalDateTime.now())) {
            log.debug("DNS cache hit for: {}", cacheKey);
            context.setAttribute("cacheHit", true);
            context.setResolved(true);
            context.setResolvedBy(getName());
            return Collections.singletonList(cached.getValue());
        }

        context.setAttribute("cacheHit", false);
        return Collections.emptyList();
    }
}
