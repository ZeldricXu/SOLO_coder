package com.datamasker.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datamasker.infrastructure.config.ShamirConfig;
import com.datamasker.domain.shamir.algorithm.ShamirSecretSharing;
import com.datamasker.domain.shamir.model.KeyShard;
import com.datamasker.domain.shamir.model.SecretRecoveryResult;
import com.datamasker.infrastructure.persistence.mapper.KeyShardMapper;
import com.datamasker.infrastructure.persistence.entity.KeyShardEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShamirService {

    private final ShamirSecretSharing shamirSecretSharing;
    private final ShamirConfig shamirConfig;
    private final KeyShardMapper keyShardMapper;

    public List<KeyShard> createShares(String secret, int threshold, int totalShares, String owner) {
        BigInteger secretValue = new BigInteger(secret.getBytes());
        BigInteger prime = shamirSecretSharing.generatePrime(shamirConfig.getPrimeBits());

        while (secretValue.compareTo(prime) >= 0) {
            prime = shamirSecretSharing.generatePrime(shamirConfig.getPrimeBits());
        }

        String secretId = UUID.randomUUID().toString().replace("-", "");
        List<KeyShard> shards = shamirSecretSharing.split(secretValue, threshold, totalShares, prime);

        for (KeyShard shard : shards) {
            shard.setSecretId(secretId);
            shard.setOwner(owner);

            KeyShardEntity po = new KeyShardEntity();
            po.setSecretId(secretId);
            po.setShardIndex(shard.getShardIndex());
            po.setShardData(shard.getShardData().toString());
            po.setThreshold(threshold);
            po.setTotalShares(totalShares);
            po.setOwner(owner);
            keyShardMapper.insert(po);
        }

        return shards;
    }

    public SecretRecoveryResult reconstructSecret(String secretId, List<Integer> shardIndices) {
        LambdaQueryWrapper<KeyShardEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeyShardEntity::getSecretId, secretId)
                .in(KeyShardEntity::getShardIndex, shardIndices);
        List<KeyShardEntity> poList = keyShardMapper.selectList(wrapper);

        if (poList.isEmpty()) {
            throw new RuntimeException("No shards found for secretId: " + secretId);
        }

        int threshold = poList.get(0).getThreshold();
        if (poList.size() < threshold) {
            throw new RuntimeException("Not enough shards: need " + threshold + ", got " + poList.size());
        }

        LambdaQueryWrapper<KeyShardEntity> allWrapper = new LambdaQueryWrapper<>();
        allWrapper.eq(KeyShardEntity::getSecretId, secretId);
        KeyShardEntity anyShard = keyShardMapper.selectOne(allWrapper.last("LIMIT 1"));

        BigInteger prime = shamirSecretSharing.generatePrime(shamirConfig.getPrimeBits());

        List<KeyShard> shards = new ArrayList<>();
        for (KeyShardEntity po : poList) {
            KeyShard shard = new KeyShard();
            shard.setSecretId(po.getSecretId());
            shard.setShardIndex(po.getShardIndex());
            shard.setShardData(new BigInteger(po.getShardData()));
            shard.setThreshold(po.getThreshold());
            shard.setTotalShares(po.getTotalShares());
            shard.setOwner(po.getOwner());
            shards.add(shard);
        }

        SecretRecoveryResult result = shamirSecretSharing.reconstruct(shards, prime);
        result.setSecretId(secretId);
        return result;
    }

    public Map<String, Object> getShardInfo(String secretId) {
        LambdaQueryWrapper<KeyShardEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeyShardEntity::getSecretId, secretId);
        List<KeyShardEntity> poList = keyShardMapper.selectList(wrapper);

        Map<String, Object> info = new HashMap<>();
        if (!poList.isEmpty()) {
            info.put("secretId", secretId);
            info.put("threshold", poList.get(0).getThreshold());
            info.put("totalShares", poList.get(0).getTotalShares());
            info.put("availableShardCount", poList.size());
            info.put("owner", poList.get(0).getOwner());
        }
        return info;
    }
}
