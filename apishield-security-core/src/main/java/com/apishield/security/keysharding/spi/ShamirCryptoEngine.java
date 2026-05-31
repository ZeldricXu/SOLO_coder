package com.apishield.security.keysharding.spi;

import com.apishield.security.keysharding.domain.KeyShare;
import com.apishield.security.keysharding.domain.RecoveryResult;
import com.apishield.security.keysharding.domain.ShardSecret;
import java.util.Map;

public interface ShamirCryptoEngine {
    ShardSecret generateShares(String secret, int threshold, int totalShares);
    RecoveryResult recoverSecret(String keyId, Map<Integer, String> shares, int threshold);
    String generateShareValue(int x, int[] coefficients, int prime);
    int modInverse(int a, int m);
}
