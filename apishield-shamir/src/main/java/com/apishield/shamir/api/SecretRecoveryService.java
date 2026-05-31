package com.apishield.shamir.api;

import com.apishield.shamir.domain.model.ShamirKeyShare;
import java.util.List;
import java.util.Map;

public interface SecretRecoveryService {
    String recoverSecret(List<ShamirKeyShare> shares);
    String recoverSecret(Map<Integer, String> shareValues, int threshold);
}
