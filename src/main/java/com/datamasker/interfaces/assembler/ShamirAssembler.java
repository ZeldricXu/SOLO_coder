package com.datamasker.interfaces.assembler;

import com.datamasker.domain.shamir.model.KeyShard;
import com.datamasker.domain.shamir.model.SecretRecoveryResult;
import com.datamasker.interfaces.dto.shamir.CreateSharesResponse;
import com.datamasker.interfaces.dto.shamir.ReconstructResponse;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

public class ShamirAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static CreateSharesResponse toCreateSharesResponse(List<KeyShard> shards) {
        CreateSharesResponse response = new CreateSharesResponse();
        if (shards == null || shards.isEmpty()) {
            return response;
        }
        response.setSecretId(shards.get(0).getSecretId());
        response.setThreshold(shards.get(0).getThreshold());
        response.setTotalShares(shards.get(0).getTotalShares());
        List<CreateSharesResponse.ShardInfo> shardInfos = shards.stream().map(shard -> {
            CreateSharesResponse.ShardInfo info = new CreateSharesResponse.ShardInfo();
            info.setIndex(shard.getShardIndex());
            info.setShardData(shard.getShardData().toString());
            return info;
        }).collect(Collectors.toList());
        response.setShards(shardInfos);
        return response;
    }

    public static ReconstructResponse toReconstructResponse(SecretRecoveryResult result) {
        ReconstructResponse response = new ReconstructResponse();
        response.setSecretId(result.getSecretId());
        response.setRecoveredSecret(result.getRecoveredSecret().toString());
        response.setParticipantCount(result.getParticipantCount());
        response.setRecoveredAt(result.getRecoveredAt().format(FORMATTER));
        return response;
    }
}
