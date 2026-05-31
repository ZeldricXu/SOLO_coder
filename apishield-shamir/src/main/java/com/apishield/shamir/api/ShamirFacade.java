package com.apishield.shamir.api;

import com.apishield.shamir.domain.model.ShamirKeyShare;
import java.util.List;
import java.util.Map;

public interface ShamirFacade extends 
        SecretSharingGenerator,
        SecretRecoveryService,
        ShareQueryService,
        ShareManagementService {
    
    default List<ShamirKeyShare> generateAndDistribute(String secret, int threshold, int totalShares, 
                                                       String keyId, List<String> ownerIds) {
        List<ShamirKeyShare> shares = generateShares(secret, threshold, totalShares, keyId);
        for (int i = 0; i < Math.min(shares.size(), ownerIds.size()); i++) {
            distributeShare(shares.get(i).getId(), ownerIds.get(i));
        }
        return shares;
    }
}
