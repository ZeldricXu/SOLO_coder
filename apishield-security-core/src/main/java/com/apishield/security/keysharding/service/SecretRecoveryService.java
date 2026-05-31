package com.apishield.security.keysharding.service;

import com.apishield.security.keysharding.api.SecretRecovery;
import com.apishield.security.keysharding.domain.RecoveryResult;
import com.apishield.security.keysharding.spi.ShamirCryptoEngine;
import com.apishield.security.keysharding.spi.ShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SecretRecoveryService implements SecretRecovery {

    private final ShamirCryptoEngine cryptoEngine;
    private final ShareRepository shareRepository;

    @Override
    public RecoveryResult recover(String keyId, Map<Integer, String> shares) {
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("KeyId cannot be blank");
        if (shares == null || shares.isEmpty()) throw new IllegalArgumentException("Shares cannot be empty");
        int threshold = shareRepository.findByKeyId(keyId).size();
        return cryptoEngine.recoverSecret(keyId, shares, threshold);
    }

    @Override
    public boolean canRecover(int availableShares, int threshold) {
        return availableShares >= threshold && threshold > 0;
    }
}
