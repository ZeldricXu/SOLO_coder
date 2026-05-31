package com.web3platform.catalog.infrastructure.persistence.memory;

import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.model.ServiceStatus;
import com.web3platform.catalog.domain.repository.ServiceRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryServiceRepository implements ServiceRepository {
    private final Map<UUID, ServiceEntry> storage = new ConcurrentHashMap<>();

    @Override
    public void save(ServiceEntry service) {
        storage.put(service.getId(), service);
    }

    @Override
    public Optional<ServiceEntry> findById(UUID id) {
        return Optional.ofNullable(storage.get(id));
    }

    @Override
    public Optional<ServiceEntry> findByName(String name) {
        return storage.values().stream()
            .filter(s -> s.getName().equalsIgnoreCase(name))
            .findFirst();
    }

    @Override
    public List<ServiceEntry> findAll() {
        return List.copyOf(storage.values());
    }

    @Override
    public List<ServiceEntry> findByLanguage(String language) {
        return storage.values().stream()
            .filter(s -> s.getLanguage().equalsIgnoreCase(language))
            .collect(Collectors.toList());
    }

    @Override
    public List<ServiceEntry> findByTeam(String team) {
        return storage.values().stream()
            .filter(s -> s.getTeam().equalsIgnoreCase(team))
            .collect(Collectors.toList());
    }

    @Override
    public List<ServiceEntry> findByStatus(ServiceStatus status) {
        return storage.values().stream()
            .filter(s -> s.getStatus() == status)
            .collect(Collectors.toList());
    }

    @Override
    public List<ServiceEntry> findByTag(String tag) {
        return storage.values().stream()
            .filter(s -> s.getTags().contains(tag))
            .collect(Collectors.toList());
    }

    @Override
    public void delete(UUID id) {
        storage.remove(id);
    }

    @Override
    public boolean exists(UUID id) {
        return storage.containsKey(id);
    }

    public void clear() {
        storage.clear();
    }
}
