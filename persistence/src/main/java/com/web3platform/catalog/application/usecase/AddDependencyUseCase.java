package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.application.dto.AddDependencyRequest;
import com.web3platform.catalog.application.dto.DependencyResponse;
import com.web3platform.catalog.domain.exception.CatalogException;
import com.web3platform.catalog.domain.model.DependencyRelation;
import com.web3platform.catalog.domain.repository.DependencyRepository;
import com.web3platform.catalog.domain.repository.ServiceRepository;

import java.util.UUID;

public class AddDependencyUseCase {
    private final DependencyRepository dependencyRepository;
    private final ServiceRepository serviceRepository;

    public AddDependencyUseCase(DependencyRepository dependencyRepository, ServiceRepository serviceRepository) {
        this.dependencyRepository = dependencyRepository;
        this.serviceRepository = serviceRepository;
    }

    public DependencyResponse execute(UUID sourceId, AddDependencyRequest request) {
        validateServicesExist(sourceId, request.getTargetId());
        
        if (sourceId.equals(request.getTargetId())) {
            throw new CatalogException("Service cannot depend on itself");
        }

        DependencyRelation relation = new DependencyRelation(
            sourceId,
            request.getTargetId(),
            request.getDepType(),
            request.getVersionConstraint()
        );

        dependencyRepository.save(relation);
        return DependencyResponse.fromDomain(relation);
    }

    private void validateServicesExist(UUID sourceId, UUID targetId) {
        if (!serviceRepository.exists(sourceId)) {
            throw new CatalogException("Source service not found: " + sourceId);
        }
        if (!serviceRepository.exists(targetId)) {
            throw new CatalogException("Target service not found: " + targetId);
        }
    }
}
