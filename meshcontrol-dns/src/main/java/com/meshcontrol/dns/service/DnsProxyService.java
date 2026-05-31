package com.meshcontrol.dns.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.meshcontrol.common.base.BaseService;
import com.meshcontrol.common.exception.BusinessException;
import com.meshcontrol.common.util.IdGenerator;
import com.meshcontrol.dns.dto.*;
import com.meshcontrol.dns.entity.DnsCache;
import com.meshcontrol.dns.entity.DnsUpstream;
import com.meshcontrol.dns.entity.DnsZone;
import com.meshcontrol.dns.mapper.DnsCacheMapper;
import com.meshcontrol.dns.mapper.DnsUpstreamMapper;
import com.meshcontrol.dns.mapper.DnsZoneMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xbill.DNS.*;

import java.net.IDN;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DnsProxyService extends BaseService<DnsUpstreamMapper, DnsUpstream> {

    private final DnsUpstreamMapper dnsUpstreamMapper;
    private final DnsZoneMapper dnsZoneMapper;
    private final DnsCacheMapper dnsCacheMapper;

    private final Cache<String, Object> localCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    private final Map<String, AtomicInteger> roundRobinCounter = new ConcurrentHashMap<>();

    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 60000;
    private static final int MIN_TTL = 1;
    private static final int MAX_TTL = 86400;
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))*$");
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");

    @Transactional
    public DnsUpstream addUpstream(UpstreamRequest request) {
        validateUpstreamRequest(request);

        DnsUpstream upstream = new DnsUpstream();
        upstream.setUpstreamId(IdGenerator.generateId("up"));
        upstream.setName(request.getName().trim());
        upstream.setAddress(request.getAddress().trim());
        upstream.setPort(normalizePort(request.getPort()));
        upstream.setProtocol(request.getProtocol() != null ? request.getProtocol().trim().toLowerCase() : "udp");
        upstream.setTimeoutMs(normalizeTimeout(request.getTimeoutMs()));
        upstream.setPriority(request.getPriority() != null ? request.getPriority() : 0);
        upstream.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        upstream.setHealthCheckEnabled(request.getHealthCheckEnabled() != null ? request.getHealthCheckEnabled() : true);
        upstream.setHealthStatus("healthy");

        dnsUpstreamMapper.insert(upstream);
        log.info("DNS upstream added: {} address: {}", upstream.getUpstreamId(), upstream.getAddress());
        return upstream;
    }

    private void validateUpstreamRequest(UpstreamRequest request) {
        if (request == null) {
            throw new BusinessException("Upstream request cannot be null");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BusinessException("Upstream name cannot be null or blank");
        }
        if (request.getName().length() > 64) {
            throw new BusinessException("Upstream name exceeds maximum length of 64");
        }
        if (request.getAddress() == null || request.getAddress().isBlank()) {
            throw new BusinessException("Upstream address cannot be null or blank");
        }
        if (request.getAddress().length() > 255) {
            throw new BusinessException("Upstream address exceeds maximum length of 255");
        }
        if (!isValidAddress(request.getAddress())) {
            throw new BusinessException("Invalid upstream address: " + request.getAddress());
        }
        if (request.getProtocol() != null && !List.of("udp", "tcp", "tls").contains(request.getProtocol().toLowerCase())) {
            throw new BusinessException("Invalid protocol: must be one of udp, tcp, tls");
        }
    }

    private boolean isValidAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        return IP_PATTERN.matcher(address).matches() || DOMAIN_PATTERN.matcher(address).matches();
    }

    private int normalizePort(Integer port) {
        if (port == null) {
            return 53;
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            log.warn("Invalid port {}, using default 53", port);
            return 53;
        }
        return port;
    }

    private int normalizeTimeout(Integer timeoutMs) {
        if (timeoutMs == null) {
            return 5000;
        }
        if (timeoutMs < MIN_TIMEOUT_MS) {
            log.warn("Timeout too small {}, using minimum {}", timeoutMs, MIN_TIMEOUT_MS);
            return MIN_TIMEOUT_MS;
        }
        if (timeoutMs > MAX_TIMEOUT_MS) {
            log.warn("Timeout too large {}, using maximum {}", timeoutMs, MAX_TIMEOUT_MS);
            return MAX_TIMEOUT_MS;
        }
        return timeoutMs;
    }

    public IPage<DnsUpstream> listUpstreams(int pageNum, int pageSize) {
        LambdaQueryWrapper<DnsUpstream> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(DnsUpstream::getPriority);
        return page(pageNum, pageSize, wrapper);
    }

    @Transactional
    public boolean deleteUpstream(String upstreamId) {
        return dnsUpstreamMapper.deleteById(upstreamId) > 0;
    }

    @Transactional
    public DnsZone addZone(ZoneRequest request) {
        DnsZone zone = new DnsZone();
        zone.setZoneId(IdGenerator.generateId("zone"));
        zone.setDomain(request.getDomain());
        zone.setUpstreamIds(request.getUpstreamIds());
        zone.setResolutionPolicy(request.getResolutionPolicy());
        zone.setCacheTtl(request.getCacheTtl());
        zone.setEnabled(request.getEnabled());

        dnsZoneMapper.insert(zone);
        log.info("DNS zone added: {}", zone.getZoneId());
        return zone;
    }

    public List<DnsZone> listZones() {
        return dnsZoneMapper.selectList(null);
    }

    @Transactional
    public boolean deleteZone(String zoneId) {
        return dnsZoneMapper.deleteById(zoneId) > 0;
    }

    public DnsQueryResponse resolve(DnsQueryRequest request) {
        validateDnsQueryRequest(request);

        String normalizedDomain = normalizeDomain(request.getDomain());
        String normalizedType = normalizeQueryType(request.getType());

        String cacheKey = normalizedDomain + ":" + normalizedType;

        DnsCache cached = dnsCacheMapper.findValidCache(cacheKey, LocalDateTime.now());
        if (cached != null) {
            dnsCacheMapper.incrementHitCount(cached.getId());
            log.debug("DNS cache hit for: {}", cacheKey);
            return new DnsQueryResponse(
                    normalizedDomain, normalizedType,
                    cached.getResponses(),
                    Math.max(0, java.time.Duration.between(LocalDateTime.now(), cached.getExpiresAt()).getSeconds()),
                    true,
                    "cache");
        }

        DnsZone zone = findBestZone(normalizedDomain);
        List<DnsUpstream> upstreams = getUpstreamsForZone(zone);

        if (upstreams.isEmpty()) {
            throw new BusinessException("No available DNS upstreams");
        }

        DnsUpstream upstream = selectUpstream(upstreams, zone != null ? zone.getResolutionPolicy() : "round_robin");

        List<Map<String, Object>> records = doResolve(normalizedDomain, normalizedType, upstream);

        int ttl = zone != null ? normalizeTtl(zone.getCacheTtl()) : 300;
        cacheResult(cacheKey, normalizedType, records, ttl);

        return new DnsQueryResponse(normalizedDomain, normalizedType, records, (long) ttl, false, upstream.getName());
    }

    private void validateDnsQueryRequest(DnsQueryRequest request) {
        if (request == null) {
            throw new BusinessException("DNS query request cannot be null");
        }
        if (request.getDomain() == null || request.getDomain().isBlank()) {
            throw new BusinessException("Domain cannot be null or blank");
        }
        String trimmedDomain = request.getDomain().trim();
        if (trimmedDomain.length() > MAX_DOMAIN_LENGTH) {
            throw new BusinessException("Domain exceeds maximum length of " + MAX_DOMAIN_LENGTH);
        }
        String[] labels = trimmedDomain.split("\\.");
        for (String label : labels) {
            if (label.length() > MAX_LABEL_LENGTH) {
                throw new BusinessException("Domain label exceeds maximum length of " + MAX_LABEL_LENGTH);
            }
        }
    }

    private String normalizeDomain(String domain) {
        if (domain == null) {
            throw new BusinessException("Domain cannot be null");
        }
        String normalized = domain.trim().toLowerCase();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            normalized = IDN.toASCII(normalized);
        } catch (Exception e) {
            log.warn("Failed to convert domain to ASCII: {}", domain);
        }
        return normalized;
    }

    private String normalizeQueryType(String type) {
        if (type == null || type.isBlank()) {
            return "A";
        }
        String upperType = type.trim().toUpperCase();
        try {
            Type.value(upperType);
            return upperType;
        } catch (Exception e) {
            log.warn("Invalid DNS record type {}, using default A", type);
            return "A";
        }
    }

    private int normalizeTtl(Integer ttl) {
        if (ttl == null) {
            return 300;
        }
        if (ttl < MIN_TTL) {
            log.warn("TTL too small {}, using minimum {}", ttl, MIN_TTL);
            return MIN_TTL;
        }
        if (ttl > MAX_TTL) {
            log.warn("TTL too large {}, using maximum {}", ttl, MAX_TTL);
            return MAX_TTL;
        }
        return ttl;
    }

    private DnsZone findBestZone(String domain) {
        DnsZone exactMatch = dnsZoneMapper.findByDomain(domain);
        if (exactMatch != null) {
            return exactMatch;
        }
        return dnsZoneMapper.findBestMatch(domain);
    }

    private List<DnsUpstream> getUpstreamsForZone(DnsZone zone) {
        if (zone != null && zone.getUpstreamIds() != null && !zone.getUpstreamIds().isEmpty()) {
            return dnsUpstreamMapper.findAllEnabled();
        }
        if (zone != null) {
            List<DnsUpstream> upstreams = new ArrayList<>();
            for (String id : zone.getUpstreamIds()) {
                upstreams.addAll(dnsUpstreamMapper.findEnabledByIds(id));
            }
            return upstreams;
        }
        return dnsUpstreamMapper.findAllEnabled();
    }

    private DnsUpstream selectUpstream(List<DnsUpstream> upstreams, String policy) {
        if (upstreams.size() == 1) {
            return upstreams.get(0);
        }
        switch (policy) {
            case "round_robin":
                return selectRoundRobin(upstreams);
            case "priority":
                return upstreams.get(0);
            case "random":
                return upstreams.get(new Random().nextInt(upstreams.size()));
            default:
                return selectRoundRobin(upstreams);
        }
    }

    private DnsUpstream selectRoundRobin(List<DnsUpstream> upstreams) {
        String key = "rr_" + String.join("_", upstreams.stream().map(DnsUpstream::getUpstreamId).toList());
        AtomicInteger counter = roundRobinCounter.computeIfAbsent(key, k -> new AtomicInteger(0));
        int index = counter.getAndIncrement() % upstreams.size();
        return upstreams.get(index);
    }

    private List<Map<String, Object>> doResolve(String domain, String type, DnsUpstream upstream) {
        validateResolveInput(domain, type, upstream);

        Lookup lookup = null;
        SimpleResolver resolver = null;
        try {
            int recordType = Type.value(type);
            lookup = new Lookup(domain, recordType);

            resolver = new SimpleResolver(upstream.getAddress());
            resolver.setPort(normalizePort(upstream.getPort()));
            resolver.setTimeout(normalizeTimeout(upstream.getTimeoutMs()));
            lookup.setResolver(resolver);

            Record[] records = lookup.run();

            if (lookup.getResult() != Lookup.SUCCESSFUL) {
                String error = lookup.getErrorString();
                log.warn("DNS lookup failed for {}: {} - {}", domain, type, error);
                return Collections.emptyList();
            }

            if (records == null || records.length == 0) {
                log.debug("No DNS records found for {}: {}", domain, type);
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = new ArrayList<>(records.length);
            for (Record record : records) {
                if (record != null) {
                    Map<String, Object> recordMap = new HashMap<>();
                    recordMap.put("type", Type.string(record.getType()));
                    recordMap.put("ttl", Math.max(0, record.getTTL()));
                    String rdata = record.rdataToString();
                    recordMap.put("data", rdata != null ? rdata : "");
                    result.add(recordMap);
                }
            }
            return result;
        } catch (TextParseException e) {
            log.error("Invalid DNS domain: {}", domain, e);
            throw new BusinessException("Invalid domain name: " + domain);
        } catch (IllegalArgumentException e) {
            log.error("Invalid DNS record type: {}", type, e);
            throw new BusinessException("Invalid DNS record type: " + type);
        } catch (Exception e) {
            log.error("DNS resolution error for {}: {}", domain, e.getMessage());
            throw new BusinessException("DNS resolution failed: " + e.getMessage());
        } finally {
            if (lookup != null) {
                try {
                    lookup.setResolver(null);
                } catch (Exception e) {
                    log.debug("Error clearing resolver", e);
                }
            }
        }
    }

    private void validateResolveInput(String domain, String type, DnsUpstream upstream) {
        if (domain == null || domain.isBlank()) {
            throw new BusinessException("Domain cannot be null or blank");
        }
        if (domain.length() > MAX_DOMAIN_LENGTH) {
            throw new BusinessException("Domain exceeds maximum length");
        }
        if (type == null || type.isBlank()) {
            throw new BusinessException("DNS record type cannot be null or blank");
        }
        if (upstream == null) {
            throw new BusinessException("DNS upstream cannot be null");
        }
        if (upstream.getAddress() == null || upstream.getAddress().isBlank()) {
            throw new BusinessException("DNS upstream address cannot be null or blank");
        }
    }

    @Transactional
    public void cacheResult(String cacheKey, String queryType, List<Map<String, Object>> responses, int ttl) {
        DnsCache cache = new DnsCache();
        cache.setCacheKey(cacheKey);
        cache.setQueryType(queryType);
        cache.setResponses(responses);
        cache.setExpiresAt(LocalDateTime.now().plusSeconds(ttl));
        cache.setHitCount(1);
        dnsCacheMapper.insert(cache);
    }

    @Scheduled(fixedRate = 300000)
    public void cleanExpiredCache() {
        int deleted = dnsCacheMapper.deleteExpired(LocalDateTime.now());
        if (deleted > 0) {
            log.debug("Cleaned {} expired DNS cache entries", deleted);
        }
    }

    @Scheduled(fixedRate = 60000)
    public void healthCheckUpstreams() {
        List<DnsUpstream> upstreams = dnsUpstreamMapper.selectList(null);
        for (DnsUpstream upstream : upstreams) {
            if (!upstream.getHealthCheckEnabled()) {
                try {
                    Lookup lookup = new Lookup("google.com", Type.A);
                    SimpleResolver resolver = new SimpleResolver(upstream.getAddress());
                    resolver.setPort(upstream.getPort());
                    resolver.setTimeout(upstream.getTimeoutMs());
                    lookup.setResolver(resolver);
                    lookup.run();

                    upstream.setHealthStatus(lookup.getResult() == Lookup.SUCCESSFUL ? "healthy" : "unhealthy");
                    upstream.setLastHealthCheck(LocalDateTime.now());
                    dnsUpstreamMapper.updateById(upstream);
                } catch (Exception e) {
                    upstream.setHealthStatus("unhealthy");
                    upstream.setLastHealthCheck(LocalDateTime.now());
                    dnsUpstreamMapper.updateById(upstream);
                }
            }
        }
    }

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("localCacheSize", localCache.estimatedSize());
        stats.put("localCacheStats", localCache.stats().toString());
        return stats;
    }
}
