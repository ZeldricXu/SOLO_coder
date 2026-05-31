package com.datamasker.application.aspect;

import com.datamasker.application.service.ShardCacheService;
import com.datamasker.domain.shamir.cache.ShardCacheEntry;
import com.datamasker.domain.shamir.model.KeyShard;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class ShardCacheAspect {

    private final ShardCacheService shardCacheService;

    @Around("@annotation(com.datamasker.domain.shamir.cache.CacheableShard)")
    public Object cacheShard(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        if (args.length >= 2 && args[0] instanceof String && args[1] instanceof Integer) {
            String secretId = (String) args[0];
            int shardIndex = (Integer) args[1];
            ShardCacheEntry cached = shardCacheService.getCachedShard(secretId, shardIndex);
            if (cached != null) {
                KeyShard shard = new KeyShard();
                shard.setSecretId(secretId);
                shard.setShardIndex(shardIndex);
                shard.setShardData(cached.getShardData());
                shard.setThreshold(cached.getThreshold());
                shard.setOwner(cached.getOwner());
                return shard;
            }
        }
        Object result = joinPoint.proceed();
        if (result instanceof KeyShard) {
            shardCacheService.cacheShard((KeyShard) result);
        }
        return result;
    }
}
