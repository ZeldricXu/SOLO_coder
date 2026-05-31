package com.web3platform.catalog.infrastructure.persistence.memory;

import com.web3platform.catalog.domain.model.DependencyRelation;
import com.web3platform.catalog.domain.repository.DependencyRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class InMemoryDependencyRepository implements DependencyRepository {
    private final List<DependencyRelation> storage = new CopyOnWriteArrayList<>();

    @Override
    public void save(DependencyRelation relation) {
        storage.removeIf(r -> 
            r.getSourceId().equals(relation.getSourceId()) && 
            r.getTargetId().equals(relation.getTargetId())
        );
        storage.add(relation);
    }

    @Override
    public void delete(DependencyRelation relation) {
        storage.removeIf(r -> 
            r.getSourceId().equals(relation.getSourceId()) && 
            r.getTargetId().equals(relation.getTargetId())
        );
    }

    @Override
    public List<DependencyRelation> findDependenciesOf(UUID serviceId) {
        return storage.stream()
            .filter(r -> r.getSourceId().equals(serviceId))
            .collect(Collectors.toList());
    }

    @Override
    public List<DependencyRelation> findDependentsOf(UUID serviceId) {
        return storage.stream()
            .filter(r -> r.getTargetId().equals(serviceId))
            .collect(Collectors.toList());
    }

    @Override
    public void deleteAllForService(UUID serviceId) {
        storage.removeIf(r -> 
            r.getSourceId().equals(serviceId) || r.getTargetId().equals(serviceId)
        );
    }

    public void clear() {
        storage.clear();
    }
}
