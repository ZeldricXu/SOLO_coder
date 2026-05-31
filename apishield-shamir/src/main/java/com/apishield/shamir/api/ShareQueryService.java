package com.apishield.shamir.api;

import com.apishield.shamir.domain.model.ShamirKeyShare;
import java.util.List;
import java.util.Optional;

public interface ShareQueryService {
    Optional<ShamirKeyShare> findById(String shareId);
    List<ShamirKeyShare> findByKeyId(String keyId);
    List<ShamirKeyShare> findByOwnerId(String ownerId);
}
