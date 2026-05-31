package com.apishield.security.keysharding.api;

import com.apishield.security.keysharding.domain.KeyShare;
import java.util.List;
import java.util.Optional;

public interface ShareQuery {
    Optional<KeyShare> findById(String shareId);
    List<KeyShare> findByKeyId(String keyId);
    List<KeyShare> findByOwnerId(String ownerId);
}
