package com.ratelimiter.service.quota;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ratelimiter.repository.QuotaRepository;
import com.ratelimiter.repository.QuotaRepository.QuotaState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class QuotaService {
    
    private final QuotaRepository quotaRepository;
    private final ConcurrentHashMap<String, LocalQuota> localQuotas;
    private final Cache<String, Object> cache;
    
    private static final int DEFAULT_DAILY_QUOTA = 10000;
    private static final String DEFAULT_PERIOD = "daily";
    
    public QuotaService(QuotaRepository quotaRepository) {
        this.quotaRepository = quotaRepository;
        this.localQuotas = new ConcurrentHashMap<>();
        this.cache = Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .build();
    }
    
    public void assignQuota(String clientId, String target, int totalQuota, String period) {
        QuotaState state = new QuotaState(totalQuota, period);
        int ttlSeconds = calculateTtlSeconds(period);
        quotaRepository.saveQuota(clientId, target, state, ttlSeconds);
        log.info("Assigned quota {} for client: {}, target: {}, period: {}", 
                totalQuota, clientId, target, period);
    }
    
    public void assignDefaultQuota(String clientId, String target) {
        assignQuota(clientId, target, DEFAULT_DAILY_QUOTA, DEFAULT_PERIOD);
    }
    
    public QuotaResult tryConsumeQuota(String clientId, String target) {
        return tryConsumeQuota(clientId, target, 1);
    }
    
    public QuotaResult tryConsumeQuota(String clientId, String target, int amount) {
        try {
            return tryConsumeWithRedis(clientId, target, amount);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis connection failed, falling back to local quota for client: {}, target: {}", 
                    clientId, target);
            return tryConsumeWithLocal(clientId, target, amount);
        } catch (Exception e) {
            log.error("Error in quota consumption for client: {}, target: {}", clientId, target, e);
            return tryConsumeWithLocal(clientId, target, amount);
        }
    }
    
    private QuotaResult tryConsumeWithRedis(String clientId, String target, int amount) {
        QuotaState state = quotaRepository.getQuota(clientId, target);
        
        if (state == null) {
            log.info("No quota found for client: {}, target: {}, using default", clientId, target);
            return QuotaResult.allowed(DEFAULT_DAILY_QUOTA - amount, DEFAULT_DAILY_QUOTA);
        }
        
        int remaining = state.getRemainingQuota();
        if (remaining < amount) {
            log.warn("Quota exceeded for client: {}, target: {}, remaining: {}, requested: {}", 
                    clientId, target, remaining, amount);
            return QuotaResult.rejected("Quota exceeded", 429);
        }
        
        state.setUsedQuota(state.getUsedQuota() + amount);
        int ttlSeconds = calculateTtlSeconds(state.getPeriod());
        quotaRepository.saveQuota(clientId, target, state, ttlSeconds);
        
        log.debug("Consumed quota for client: {}, target: {}, used: {}/{}", 
                clientId, target, state.getUsedQuota(), state.getTotalQuota());
        
        return QuotaResult.allowed(state.getRemainingQuota(), state.getTotalQuota());
    }
    
    private QuotaResult tryConsumeWithLocal(String clientId, String target, int amount) {
        String key = clientId + ":" + target;
        LocalQuota localQuota = localQuotas.computeIfAbsent(key, 
                k -> new LocalQuota(DEFAULT_DAILY_QUOTA));
        
        synchronized (localQuota) {
            int remaining = localQuota.getRemaining();
            if (remaining < amount) {
                log.warn("Local quota exceeded for client: {}, target: {}, remaining: {}, requested: {}",
                        clientId, target, remaining, amount);
                return QuotaResult.rejected("Quota exceeded", 429);
            }
            
            localQuota.consume(amount);
            return QuotaResult.allowed(localQuota.getRemaining(), localQuota.getTotal());
        }
    }
    
    public QuotaState getQuotaState(String clientId, String target) {
        return quotaRepository.getQuota(clientId, target);
    }
    
    public void resetQuota(String clientId, String target) {
        quotaRepository.deleteQuota(clientId, target);
        String key = clientId + ":" + target;
        localQuotas.remove(key);
        log.info("Reset quota for client: {}, target: {}", clientId, target);
    }
    
    private int calculateTtlSeconds(String period) {
        switch (period != null ? period.toLowerCase() : "") {
            case "hourly":
                return 3600;
            case "daily":
                return 86400;
            case "weekly":
                return 604800;
            case "monthly":
                return 2592000;
            default:
                return 86400;
        }
    }
    
    private static class LocalQuota {
        private final int total;
        private final AtomicInteger used;
        
        public LocalQuota(int total) {
            this.total = total;
            this.used = new AtomicInteger(0);
        }
        
        public int getTotal() {
            return total;
        }
        
        public int getRemaining() {
            return total - used.get();
        }
        
        public void consume(int amount) {
            used.addAndGet(amount);
        }
    }
}