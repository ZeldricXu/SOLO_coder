package com.apishield.security.keysharding.service;

import com.apishield.security.keysharding.api.ShareManagement;
import com.apishield.security.keysharding.domain.ShardSecret;
import com.apishield.security.keysharding.spi.ShamirCryptoEngine;
import com.apishield.security.keysharding.spi.ShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShareManagementService implements ShareManagement {

    private final ShareRepository shareRepository;
    private final ShamirCryptoEngine cryptoEngine;

    @Override
    @Transactional
    public void deactivateShare(String shareId) {
        if (shareId == null || shareId.isBlank()) throw new IllegalArgumentException("ShareId cannot be blank");
        shareRepository.deactivateShare(shareId);
    }

    @Override
    @Transactional
    public void deleteSharesByKeyId(String keyId) {
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("KeyId cannot be blank");
        shareRepository.deleteByKeyId(keyId);
    }

    @Override
    @Transactional
    public void rotateShares(String keyId, String newSecret) {
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("KeyId cannot be blank");
        if (newSecret == null || newSecret.isBlank()) throw new IllegalArgumentException("New secret cannot be blank");

        var existingShares = shareRepository.findByKeyId(keyId);
        if (existingShares.isEmpty()) throw new IllegalArgumentException("No shares found for keyId: " + keyId);

        int threshold = Math.min(existingShares.size(), 3);
        int total = Math.max(existingShares.size(), 5);

        shareRepository.deleteByKeyId(keyId);
        ShardSecret newShard = cryptoEngine.generateShares(newSecret, threshold, total);
        newShard.getShares().forEach(shareRepository::save);
    }
}
