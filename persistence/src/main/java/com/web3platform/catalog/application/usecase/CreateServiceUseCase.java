package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.application.dto.CreateServiceRequest;
import com.web3platform.catalog.application.dto.ServiceResponse;
import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.repository.ServiceRepository;
import com.web3platform.catalog.domain.exception.CatalogException;

public class CreateServiceUseCase {
    private final ServiceRepository serviceRepository;

    public CreateServiceUseCase(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    public ServiceResponse execute(CreateServiceRequest request) {
        validateRequest(request);
        
        serviceRepository.findByName(request.getName())
            .ifPresent(s -> {
                throw new CatalogException("Service with name '" + request.getName() + "' already exists");
            });

        ServiceEntry service = ServiceEntry.create(
            request.getName(),
            request.getDescription(),
            request.getLanguage(),
            request.getOwner(),
            request.getTeam(),
            request.getRepositoryUrl(),
            request.getVersion()
        );

        if (request.getApiDocUrl() != null) {
            service.update(
                service.getName(),
                service.getDescription(),
                service.getLanguage(),
                service.getOwner(),
                service.getTeam(),
                service.getRepositoryUrl(),
                request.getApiDocUrl(),
                service.getStatus(),
                service.getVersion()
            );
        }

        if (request.getTags() != null) {
            request.getTags().forEach(service::addTag);
        }

        serviceRepository.save(service);
        return ServiceResponse.fromDomain(service);
    }

    private void validateRequest(CreateServiceRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new CatalogException("Service name is required");
        }
        if (request.getOwner() == null || request.getOwner().isBlank()) {
            throw new CatalogException("Service owner is required");
        }
        if (request.getTeam() == null || request.getTeam().isBlank()) {
            throw new CatalogException("Service team is required");
        }
    }
}
