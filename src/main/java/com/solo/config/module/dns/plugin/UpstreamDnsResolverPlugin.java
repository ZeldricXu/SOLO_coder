package com.solo.config.module.dns.plugin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.solo.config.entity.DnsRecord;
import com.solo.config.mapper.DnsRecordMapper;
import com.solo.config.module.dns.DnsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpstreamDnsResolverPlugin implements DnsResolverPlugin {

    private final DnsProperties properties;
    private final DnsRecordMapper dnsRecordMapper;
    private final Cache<String, DnsRecord> dnsCache;

    @Override
    public String getName() {
        return "upstream";
    }

    @Override
    public int getPriority() {
        return 30;
    }

    @Override
    public List<String> resolve(String domain, String recordType, DnsResolutionContext context) {
        List<DnsProperties.Upstream> sortedUpstreams = properties.getUpstreams().stream()
                .sorted(Comparator.comparingInt(DnsProperties.Upstream::getPriority))
                .toList();

        String resolved = null;
        String usedUpstream = null;

        for (DnsProperties.Upstream upstream : sortedUpstreams) {
            try {
                if ("A".equals(recordType)) {
                    InetAddress[] addresses = InetAddress.getAllByName(domain);
                    if (addresses.length > 0) {
                        resolved = addresses[0].getHostAddress();
                        usedUpstream = upstream.getHost() + ":" + upstream.getPort();
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to resolve from upstream: {}:{}, domain: {}",
                        upstream.getHost(), upstream.getPort(), domain, e);
            }
        }

        if (resolved != null) {
            DnsRecord record = new DnsRecord();
            record.setDomain(domain);
            record.setRecordType(recordType);
            record.setValue(resolved);
            record.setTtl(properties.getCache().getTtl());
            record.setUpstream(usedUpstream);
            record.setCachedAt(LocalDateTime.now());
            record.setExpiresAt(LocalDateTime.now().plusSeconds(properties.getCache().getTtl()));
            dnsRecordMapper.insert(record);

            String cacheKey = domain + ":" + recordType;
            dnsCache.put(cacheKey, record);

            context.setAttribute("upstream", usedUpstream);
            context.setResolved(true);
            context.setResolvedBy(getName());
            return Collections.singletonList(resolved);
        }

        return Collections.emptyList();
    }
}
