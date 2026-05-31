package com.apishield.security.keysharding.service;

import com.apishield.security.keysharding.api.ShareGenerator;
import com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apishield.security.keysharimport com.apirCryptoEngine cryptoEngine;
    private final ShareRepository shareRepository;

    @Override
    public ShardSecret generate(String secret, int threshold, int totalShares) {
        validateParameters(secret, threshold, totalShares);
        ShardSecret shardSecret = cryptoEngine.generateShares(secret, threshold, totalShares);
        for (KeyShare share : shardSecret.getShares()) {
            shareRepository.save(share);
        }
        return shardSecret;
    }

    private void validateParameters(String secret, int threshold, int totalShares) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("Secret cannot be blank");
        if (threshold < 2) throw new IllegalArgumentException("Threshold must be at least 2");
        if (totalShares < threshold) throw new IllegalArgumentException("Total shares must be >= threshold");
        if (totalShares > 255) throw new IllegalArgumentException("Total shares cannot exceed 255");
    }
}
