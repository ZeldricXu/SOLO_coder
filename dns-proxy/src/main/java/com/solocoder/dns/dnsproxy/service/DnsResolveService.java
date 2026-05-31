package com.solocoder.dns.dnsproxy.service;

import com.solocoder.dns.common.exception.BusinessException;
import com.solocoder.dns.dnsproxy.model.DnsResolveRequest;
import com.solocoder.dns.dnsproxy.model.DnsResolveResponse;
import com.solocoder.dns.dnsproxy.model.DnsUpstream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class DnsResolveService {
    private final DnsUpstreamService upstreamService;
    private final DnsCacheService cacheService;

    public Mono<DnsResolveResponse> resolve(DnsResolveRequest request) {
        long startTime = System.currentTimeMillis();
        String domain = request.getDomain().toLowerCase();
        Integer type = request.getRecordType() != null ? request.getRecordType() : 1;

        if (!request.getSkipCache()) {
            Optional<DnsCacheEntry> cached = cacheService.getFromCache(domain, type);
            if (cached.isPresent()) {
                DnsResolveResponse resp = buildResponse(domain, type, cached.get(), startTime);
                resp.setFromCache(true);
                return Mono.just(resp);
            }
        }

        return resolveFromUpstream(domain, type, startTime);
    }

    private Mono<DnsResolveResponse> resolveFromUpstream(String domain, Integer type, long startTime) {
        List<DnsUpstream> upstreams = upstreamService.getEnabledUpstreams();
        if (upstreams.isEmpty()) {
            return Mono.error(new BusinessException(503, "没有可用的上游DNS服务器"));
        }

        DnsUpstream selected = selectUpstream(upstreams);
        log.debug("Resolving {} type {} via upstream {}", domain, type, selected.getName());

        return Mono.fromCallable(() -> {
            try {
                String recordData = simulateDnsResolve(domain, type, selected);
                long ttl = 300L;
                cacheService.putToCache(domain, type, recordData, ttl);

                DnsResolveResponse response = new DnsResolveResponse();
                response.setDomain(domain);
                response.setRecordType(type);
                response.setRecords(Arrays.asList(recordData.split(",")));
                response.setTtl(ttl);
                response.setFromCache(false);
                response.setUpstreamUsed(selected.getName());
                response.setResolveTimeMs(System.currentTimeMillis() - startTime);
                response.setResolvedAt(LocalDateTime.now());
                return response;
            } catch (Exception e) {
                log.error("DNS resolution failed for {} via {}: {}", domain, selected.getName(), e.getMessage());
                return fallbackResolve(domain, type, startTime, selected);
            }
        });
    }

    private DnsUpstream selectUpstream(List<DnsUpstream> upstreams) {
        int totalWeight = upstreams.stream().mapToInt(DnsUpstream::getWeight).sum();
        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int current = 0;
        for (DnsUpstream upstream : upstreams) {
            current += upstream.getWeight();
            if (random < current) {
                return upstream;
            }
        }
        return upstreams.get(0);
    }

    private String simulateDnsResolve(String domain, Integer type, DnsUpstream upstream) {
        if (type == 1) {
            return generateRandomIp();
        } else if (type == 28) {
            return generateRandomIpv6();
        }
        return "default-record";
    }

    private String generateRandomIp() {
        Random r = ThreadLocalRandom.current();
        return r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(256);
    }

    private String generateRandomIpv6() {
        Random r = ThreadLocalRandom.current();
        return String.format("2001:%04x:%04x:%04x:%04x:%04x:%04x:%04x",
                r.nextInt(0xffff), r.nextInt(0xffff), r.nextInt(0xffff),
                r.nextInt(0xffff), r.nextInt(0xffff), r.nextInt(0xffff), r.nextInt(0xffff));
    }

    private DnsResolveResponse buildResponse(String domain, Integer type, DnsCacheEntry cached, long startTime) {
        DnsResolveResponse response = new DnsResolveResponse();
        response.setDomain(domain);
        response.setRecordType(type);
        response.setRecords(Arrays.asList(cached.getRecordData().split(",")));
        response.setTtl(java.time.Duration.between(LocalDateTime.now(), cached.getExpiresAt()).getSeconds());
        response.setResolveTimeMs(System.currentTimeMillis() - startTime);
        response.setResolvedAt(LocalDateTime.now());
        return response;
    }

    private DnsResolveResponse fallbackResolve(String domain, Integer type, long startTime, DnsUpstream failed) {
        DnsResolveResponse response = new DnsResolveResponse();
        response.setDomain(domain);
        response.setRecordType(type);
        response.setRecords(Collections.singletonList("fallback-ip"));
        response.setTtl(60L);
        response.setFromCache(false);
        response.setUpstreamUsed("fallback");
        response.setResolveTimeMs(System.currentTimeMillis() - startTime);
        response.setResolvedAt(LocalDateTime.now());
        log.warn("Fallback DNS resolution used for {}", domain);
        return response;
    }
}
