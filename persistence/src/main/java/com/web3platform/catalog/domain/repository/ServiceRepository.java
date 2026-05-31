package com.web3platform.catalog.domain.repository;

import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.model.ServiceStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRepository {
    void save(ServiceEntry service);
    
    Optional<ServiceEntry> findById(UUID id);
    
    Optional<ServiceEntry> findByName(String name);
    
    List<ServiceEntry> findAll();
    
    List<ServiceEntry> findByLanguage(String language);
    
    List<ServiceEntry> findByTeam(String team);
    
    List<ServiceEntry> findByStatus(ServiceStatus status);
    
    List<ServiceEntry> findByTag(String tag);
    
    void delete(UUID id);
    
    boolean exists(UUID id);
}
