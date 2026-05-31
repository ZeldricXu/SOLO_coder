package com.web3platform.catalog.infrastructure.persistence.mybatis;

import com.web3platform.catalog.domain.model.DependencyRelation;
import com.web3platform.catalog.domain.repository.DependencyRepository;
import com.web3platform.catalog.infrastructure.persistence.mybatis.entity.DependencyRelationPO;
import com.web3platform.catalog.infrastructure.persistence.mybatis.mapper.DependencyRelationMapper;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class MyBatisDependencyRepository implements DependencyRepository {
    private final DependencyRelationMapper mapper;

    public MyBatisDependencyRepository(DependencyRelationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(DependencyRelation relation) {
        mapper.delete(relation.getSourceId().toString(), relation.getTargetId().toString());
        mapper.insert(DependencyRelationPO.fromDomain(relation));
    }

    @Override
    public void delete(DependencyRelation relation) {
        mapper.delete(relation.getSourceId().toString(), relation.getTargetId().toString());
    }

    @Override
    public List<DependencyRelation> findDependenciesOf(UUID serviceId) {
        return mapper.findDependenciesOf(serviceId.toString()).stream()
            .map(DependencyRelationPO::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public List<DependencyRelation> findDependentsOf(UUID serviceId) {
        return mapper.findDependentsOf(serviceId.toString()).stream()
            .map(DependencyRelationPO::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public void deleteAllForService(UUID serviceId) {
        mapper.deleteAllForService(serviceId.toString());
    }
}
