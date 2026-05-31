package com.apishield.security.keysharding.service;

import com.apishield.security.keysharding.api.ShareQuery;
import com.apishield.security.keysharding.domain.KeyShare;
import com.apishield.security.keysharding.spi.ShareRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ShareQueryService implements ShareQuery {

    private final ShareRepository shareRepository;

    @Override
    public Optional<KeyShare> findById(String shareId) {
        if (shareId == null || shareId.isBlank()) return Optional.empty();
        return shareRepository.findById(shareId);
    }

    @Override
    public List<KeyShare> findByKeyId(String keyId) {
        if (keyId == null || keyId.isBlank()) return List.of();
        return shareRepository.findByKeyId(keyId);
    }

    @Override
    public List<KeyShare> findByOwnerId(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) return List.of();
        return shareRepository.findByOwnerId(ownerId);
    }
}
