package com.apishield.shamir.api;

import com.apishield.shamir.domain.model.ShamirKeyShare;

public interface ShareManagementService {
    void distributeShare(String shareId, String ownerId);
    void revokeShare(String shareId);
    ShamirKeyShare updateShareStatus(String shareId, ShamirKeyShare.ShareStatus status);
}
