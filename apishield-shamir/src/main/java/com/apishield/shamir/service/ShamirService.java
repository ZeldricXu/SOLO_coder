package com.apishield.shamir.service;

import com.apishield.shamir.domain.ShamirKeyShare;
import com.apishield.shamir.dto.ShamirGenerateRequest;
import com.apishield.shamir.dto.ShamirRecoverRequest;
import com.apishield.application.service.ApplicationService;
import java.util.List;
import java.util.Map;

public interface ShamirService extends ApplicationService {
    List<ShamirKeyShare> generateShares(ShamirGenerateRequest request);
    String recoverSecret(ShamirRecoverRequest request);
    ShamirKeyShare getShareById(String id);
    List<ShamirKeyShare> getSharesByKeyId(String keyId);
    void distributeShare(String shareId, String ownerId);
}
