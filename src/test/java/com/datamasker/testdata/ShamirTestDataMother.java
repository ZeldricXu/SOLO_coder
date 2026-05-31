package com.datamasker.testdata;

import com.datamasker.domain.shamir.model.KeyShard;
import com.datamasker.domain.shamir.model.SecretRecoveryResult;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ShamirTestDataMother {

    public static final String DEFAULT_SECRET = "test-secret-1234567890";
    public static final int DEFAULT_THRESHOLD = 3;
    public static final int DEFAULT_TOTAL_SHARES = 5;
    public static final String DEFAULT_OWNER = "test-owner";

    public static String secret() {
        return DEFAULT_SECRET;
    }

    public static String secret(String suffix) {
        return DEFAULT_SECRET + "-" + suffix;
    }

    public static KeyShardBuilder keyShardBuilder() {
        return new KeyShardBuilder();
    }

    public static List<KeyShard> completeShardSet() {
        List<KeyShard> shards = new ArrayList<>();
        BigInteger prime = new BigInteger("208351617316091241234326746312124448251235562226470491514186331217050270460481");
        for (int i = 1; i <= DEFAULT_TOTAL_SHARES; i++) {
            shards.add(keyShardBuilder()
                    .withSecretId("secret-" + System.currentTimeMillis())
                    .withShardIndex(i)
                    .withShardData(BigInteger.valueOf(i * 1000).mod(prime))
                    .withThreshold(DEFAULT_THRESHOLD)
                    .withTotalShares(DEFAULT_TOTAL_SHARES)
                    .withOwner(DEFAULT_OWNER)
                    .build());
        }
        return shards;
    }

    public static SecretRecoveryResult recoveryResult() {
        return new SecretRecoveryResult(
                "test-secret-id",
                new BigInteger(DEFAULT_SECRET.getBytes()),
                LocalDateTime.now(),
                DEFAULT_THRESHOLD
        );
    }

    public static class KeyShardBuilder {
        private String secretId = "test-secret-id";
        private int shardIndex = 1;
        private BigInteger shardData = BigInteger.valueOf(123456);
        private int threshold = DEFAULT_THRESHOLD;
        private int totalShares = DEFAULT_TOTAL_SHARES;
        private String owner = DEFAULT_OWNER;

        public KeyShardBuilder withSecretId(String secretId) {
            this.secretId = secretId;
            return this;
        }

        public KeyShardBuilder withShardIndex(int shardIndex) {
            this.shardIndex = shardIndex;
            return this;
        }

        public KeyShardBuilder withShardData(BigInteger shardData) {
            this.shardData = shardData;
            return this;
        }

        public KeyShardBuilder withThreshold(int threshold) {
            this.threshold = threshold;
            return this;
        }

        public KeyShardBuilder withTotalShares(int totalShares) {
            this.totalShares = totalShares;
            return this;
        }

        public KeyShardBuilder withOwner(String owner) {
            this.owner = owner;
            return this;
        }

        public KeyShard build() {
            return new KeyShard(secretId, shardIndex, shardData, threshold, totalShares, owner);
        }
    }
}
