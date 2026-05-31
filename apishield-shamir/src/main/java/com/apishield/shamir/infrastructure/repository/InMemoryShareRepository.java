package com.apishield.shamir.infrastructure.repository;

import com.apishield.shamir.domain.model.ShamirKeyShare;
import com.apishield.shamir.domain.repository.ShareRepository;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryShareRepository implements ShareRepository {

    private final Map<String, ShamirKeyShare> store = new ConcurrentHashMap<>();

    @Override
    public ShamirKeyShare save(ShamirKeyShare share) {
        store.put(share.getId(), share);
        return share;
    }

    @Override
    public Optional<ShamirKeyShare> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<ShamirKeyShare> findByKeyId(String keyId) {
        return store.values().stream()
                .filter(s -> keyId.equals(s.getKeyId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ShamirKeyShare> findByOwnerId(String ownerId) {
        return store.values().stream()
                .filter(s -> ownerId.equals(s.getOwnerId()))
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }

    @Override
    public boolean existsById(String id) {
        return store.containsKey(id);
    }
}
