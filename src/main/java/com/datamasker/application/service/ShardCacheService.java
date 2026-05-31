package com.datamasker.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datamasker.domain.shamir.cache.CacheStats;
import com.datamasker.domain.shamir.cache.MultiLevelShardCache;
import com.datamasker.domain.shamir.cache.ShardCacheEntry;
import com.datamasker.domain.shamir.model.KeyShard;
import com.datamasker.infrastructure.persistence.entity.KeyShardEntity;
import com.datamasker.infrastructure.persistence.mapper.KeyShardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShardCacheService {

    private final MultiLevelShardCache multiLevelShardCache;
    private final KeyShardMapper keyShardMapper;

    public ShardCacheEntry getCachedShard(String secretId, int shardIndex) {
        ShardCacheEntry entry = multiLevelShardCache.get(secretId, shardIndex);
        if (entry == null) {
            KeyShard shard = loadShardFromDb(secretId, shardIndex);
            if (shard != null) {
                cacheShard(shard);
                return multiLevelShardCache.get(secretId, shardIndex);
            }
        }
        return entry;
    }

    public void cacheShard(KeyShard shard) {
        ShardCacheEntry entry = new ShardCacheEntry();
        entry.setShardData(shard.getShardData());
        entry.setThreshold(shard.getThreshold());
        entry.setOwner(shard.getOwner());
        multiLevelShardCache.put(shard.getSecretId(), shard.getShardIndex(), entry);
    }

    public void warmupCache() {
        List<KeyShard> allShards = loadAllShardsFromDb();
        multiLevelShardCache.preheat(allShards);
    }

    public void invalidateShard(String secretId, int shardIndex) {
        multiLevelShardCache.invalidate(secretId, shardIndex);
    }

    public void invalidateSecret(String secretId) {
        multiLevelShardCache.invalidateBySecretId(secretId);
    }

    public void invalidateAll() {
        multiLevelShardCache.invalidateAll();
    }

    public CacheStats getCacheStats() {
        return multiLevelShardCache.getStats();
    }

    private KeyShard loadShardFromDb(String secretId, int shardIndex) {
        LambdaQueryWrapper<KeyShardEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeyShardEntity::getSecretId, secretId)
                .eq(KeyShardEntity::getShardIndex, shardIndex);
        KeyShardEntity entity = keyShardMapper.selectOne(wrapper);
        if (entity == null) {
            return null;
        }
        return toDomain(entity);
    }

    private List<KeyShard> loadAllShardsFromDb() {
        List<KeyShardEntity> entities = keyShardMapper.selectList(null);
        return entities.stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    private KeyShard toDomain(KeyShardEntity entity) {
        KeyShard shard = new KeyShard();
        shard.setSecretId(entity.getSecretId());
        shard.setShardIndex(entity.getShardIndex());
        shard.setShardData(new BigInteger(entity.getShardData()));
        shard.setThreshold(entity.getThreshold());
        shard.setTotalShares(entity.getTotalShares());
        shard.setOwner(entity.getOwner());
        return shard;
    }
}
