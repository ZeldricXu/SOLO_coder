package com.apishield.security.keysharding.api;

import com.apishield.security.keysharding.domain.RecoveryResult;
import java.util.Map;

public interface SecretRecovery {
    RecoveryResult recover(String keyId, Map<Integer, String> shares);
    boolean canRecover(int availableShares, int threshold);
}
