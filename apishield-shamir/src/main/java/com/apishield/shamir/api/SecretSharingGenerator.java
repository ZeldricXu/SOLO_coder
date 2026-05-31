package com.apishield.shamir.api;

import com.apishield.shamir.domain.model.ShamirKeyShare;
import java.util.List;

public interface SecretSharingGenerator {
    List<ShamirKeyShare> generateShares(String secret, int threshold, int totalShares);
    List<ShamirKeyShare> generateShares(String secret, int threshold, int totalShares, String keyId);
}
