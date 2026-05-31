package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.domain.exception.ServiceEntryNotFoundException;
import com.web3platform.catalog.domain.repository.DependencyRepository;
import com.web3platform.catalog.domain.repository.ServiceRepository;

import java.util.UUID;

public class DeleteServiceUseCase {
    private final ServiceRepository serviceRepository;
    private final DependencyRepository dependencyRepository;

    public DeleteServiceUseCase(ServiceRepository serviceRepository, DependencyRepository dependencyRepository) {
        this.serviceRepository = serviceRepository;
        this.dependencyRepository = dependencyRepository;
    }

    public void execute(UUID serviceId) {
        if (!serviceRepository.exists(serviceId)) {
            throw ServiceEntryNotFoundException.forId(serviceId);
        }
        dependencyRepository.deleteAllForService(serviceId);
        serviceRepository.delete(serviceId);
    }
}
