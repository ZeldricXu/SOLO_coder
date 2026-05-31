package com.web3platform.catalog.infrastructure.persistence.mybatis;

import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.model.ServiceStatus;
import com.web3platform.catalog.domain.repository.ServiceRepository;
import com.web3platform.catalog.infrastructure.persistence.mybatis.entity.ServiceEntryPO;
import com.web3platform.catalog.infrastructure.persistence.mybatis.mapper.ServiceEntryMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class MyBatisServiceRepository implements ServiceRepository {
    private final ServiceEntryMapper mapper;

    public MyBatisServiceRepository(ServiceEntryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(ServiceEntry service) {
        String id = service.getId().toString();
        if (mapper.exists(id)) {
            mapper.update(ServiceEntryPO.fromDomain(service));
        } else {
            mapper.insert(ServiceEntryPO.fromDomain(service));
        }
    }

    @Override
    public Optional<ServiceEntry> findById(UUID id) {
        return mapper.findById(id.toString())
            .map(ServiceEntryPO::toDomain);
    }

    @Override
    public Optional<ServiceEntry> findByName(String name) {
        return mapper.findByName(name)
            .map(ServiceEntryPO::toDomain);
    }

    @Override
    public List<ServiceEntry> findAll() {
        return mapper.findAll().stream()
            .map(ServiceEntryPO::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public List<ServiceEntry> findByLanguage(String language) {
        return mapper.findByLanguage(language).stream()
            .map(ServiceEntryPO::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public List<ServiceEntry> findByTeam(String team) {
        return mapper.findByTeam(team).stream()
            .map(ServiceEntryPO::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public List<ServiceEntry> findByStatus(ServiceStatus status) {
        return mapper.findByStatus(status.name()).stream()
            .map(ServiceEntryPO::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public List<ServiceEntry> findByTag(String tag) {
        return findAll().stream()
            .filter(s -> s.getTags().contains(tag))
            .collect(Collectors.toList());
    }

    @Override
    public void delete(UUID id) {
        mapper.delete(id.toString());
    }

    @Override
    public boolean exists(UUID id) {
        return mapper.exists(id.toString());
    }
}
