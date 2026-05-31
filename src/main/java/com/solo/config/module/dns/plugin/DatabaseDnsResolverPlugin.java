package com.solo.config.module.dns.plugin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.solo.config.entity.DnsRecord;
import com.solo.config.mapper.DnsRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseDnsResolverPlugin implements DnsResolverPlugin {

    private final DnsRecordMapper dnsRecordMapper;
    private final Cache<String, DnsRecord> dnsCache;

    @Override
    public String getName() {
        return "database";
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public List<String> resolve(String domain, String recordType, DnsResolutionContext context) {
        DnsRecord dbRecord = dnsRecordMapper.selectOne(
                new QueryWrapper<DnsRecord>()
                        .eq("domain", domain)
                        .eq("record_type", recordType)
                        .gt("expires_at", LocalDateTime.now())
                        .orderByDesc("cached_at")
                        .last("LIMIT 1")
        );

        if (dbRecord != null) {
            String cacheKey = domain + ":" + recordType;
            dnsCache.put(cacheKey, dbRecord);
            log.debug("DNS database hit for: {}", cacheKey);
            context.setAttribute("databaseHit", true);
            context.setResolved(true);
            context.setResolvedBy(getName());
            return Collections.singletonList(dbRecord.getValue());
        }

        context.setAttribute("databaseHit", false);
        return Collections.emptyList();
    }
}
