package com.apishield.shamir.domain.repository;

import com.apishield.shamir.domain.model.ShamirKeyShare;
import java.util.List;
import java.util.Optional;

public interface ShareRepository {
    ShamirKeyShare save(ShamirKeyShare share);
    Optional<ShamirKeyShare> findById(String id);
    List<ShamirKeyShare> findByKeyId(String keyId);
    List<ShamirKeyShare> findByOwnerId(String ownerId);
    void deleteById(String id);
    boolean existsById(String id);
}
