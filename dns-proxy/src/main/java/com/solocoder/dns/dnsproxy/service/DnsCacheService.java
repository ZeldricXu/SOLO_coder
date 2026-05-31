package com.solocoder.dns.dnsproxy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solocoder.dns.common.cache.CacheManager;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.dnsproxy.model.DnsCacheEntry;
import com.solocoder.dns.persistence.entity.DnsCachePO;
import com.solocoder.dns.persistence.mapper.DnsCacheMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DnsCacheService {
    private final DnsCacheMapper mapper;
    private final CacheManager cacheManager;

    private static final String CACHE_PREFIX = "dns:cache:";

    public Optional<DnsCacheEntry> getFromCache(String domain, Integer recordType) {
        String key = CACHE_PREFIX + domain + ":" + recordType;
        DnsCacheEntry cached = cacheManager.get(key, DnsCacheEntry.class);
        if (cached != null && cached.getExpiresAt().isAfter(LocalDateTime.now())) {
            cached.setHitCount(cached.getHitCount() + 1);
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    public void putToCache(String domain, Integer recordType, String recordData, Long ttl) {
        DnsCacheEntry entry = new DnsCacheEntry();
        entry.setId(IdGenerator.generateId("cache"));
        entry.setDomain(domain);
        entry.setRecordType(recordType);
        entry.setRecordData(recordData);
        entry.setTtl(ttl);
        entry.setExpiresAt(LocalDateTime.now().plusSeconds(ttl));
        entry.setCreatedAt(LocalDateTime.now());
        entry.setHitCount(0);

        String key = CACHE_PREFIX + domain + ":" + recordType;
        cacheManager.put(key, entry);
        mapper.insert(toPO(entry));
    }

    public void invalidateCache(String domain, Integer recordType) {
        String key = CACHE_PREFIX + domain + ":" + recordType;
        cacheManager.invalidate(key);
        LambdaQueryWrapper<DnsCachePO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DnsCachePO::getDomain, domain).eq(DnsCachePO::getRecordType, recordType);
        mapper.delete(wrapper);
        log.debug("DNS cache invalidated: {} type {}", domain, recordType);
    }

    public void cleanExpiredCache() {
        LambdaQueryWrapper<DnsCachePO> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(DnsCachePO::getExpiresAt, LocalDateTime.now());
        Long deleted = mapper.delete(wrapper);
        log.info("Cleaned {} expired DNS cache entries", deleted);
    }

    public long getCacheSize() {
        return mapper.selectCount(null);
    }

    private DnsCachePO toPO(DnsCacheEntry entry) {
        DnsCachePO po = new DnsCachePO();
        po.setId(entry.getId());
        po.setDomain(entry.getDomain());
        po.setRecordType(entry.getRecordType());
        po.setRecordData(entry.getRecordData());
        po.setTtl(entry.getTtl());
        po.setExpiresAt(entry.getExpiresAt());
        po.setCreatedAt(entry.getCreatedAt());
        po.setHitCount(entry.getHitCount());
        return po;
    }

    private DnsCacheEntry toDomain(DnsCachePO po) {
        DnsCacheEntry entry = new DnsCacheEntry();
        entry.setId(po.getId());
        entry.setDomain(po.getDomain());
        entry.setRecordType(po.getRecordType());
        entry.setRecordData(po.getRecordData());
        entry.setTtl(po.getTtl());
        entry.setExpiresAt(po.getExpiresAt());
        entry.setCreatedAt(po.getCreatedAt());
        entry.setHitCount(po.getHitCount());
        return entry;
    }
}
