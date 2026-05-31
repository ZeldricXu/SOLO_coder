package com.apishield.security.keysharding.spi;

import com.apishield.security.keysharding.domain.KeyShare;
import java.util.List;
import java.util.Optional;

public interface ShareRepository {
    void save(KeyShare share);
    Optional<KeyShare> findById(String shareId);
    List<KeyShare> findByKeyId(String keyId);
    List<KeyShare> findByOwnerId(String ownerId);
    void deleteByKeyId(String keyId);
    void deactivateShare(String shareId);
}
