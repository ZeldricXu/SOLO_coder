package com.datamasker.interfaces.dto.shamir;

import lombok.Data;

import java.util.List;

@Data
public class CreateSharesResponse {
    private String secretId;
    private List<ShardInfo> shards;
    private int threshold;
    private int totalShares;

    @Data
    public static class ShardInfo {
        private int index;
        private String shardData;
    }
}
