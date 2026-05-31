package com.web3platform.catalog.domain.repository;

import com.web3platform.catalog.domain.model.DependencyRelation;

import java.util.List;
import java.util.UUID;

public interface DependencyRepository {
    void save(DependencyRelation relation);
    
    void delete(DependencyRelation relation);
    
    List<DependencyRelation> findDependenciesOf(UUID serviceId);
    
    List<DependencyRelation> findDependentsOf(UUID serviceId);
    
    void deleteAllForService(UUID serviceId);
}
