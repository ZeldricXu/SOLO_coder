package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.application.dto.UpdateServiceRequest;
import com.web3platform.catalog.application.dto.ServiceResponse;
import com.web3platform.catalog.domain.exception.ServiceEntryNotFoundException;
import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.model.ServiceStatus;
import com.web3platform.catalog.domain.repository.ServiceRepository;

import java.util.UUID;

public class UpdateServiceUseCase {
    private final ServiceRepository serviceRepository;

    public UpdateServiceUseCase(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    public ServiceResponse execute(UUID serviceId, UpdateServiceRequest request) {
        ServiceEntry service = serviceRepository.findById(serviceId)
            .orElseThrow(() -> ServiceEntryNotFoundException.forId(serviceId));

        String name = request.getName() != null ? request.getName() : service.getName();
        String description = request.getDescription() != null ? request.getDescription() : service.getDescription();
        String language = request.getLanguage() != null ? request.getLanguage() : service.getLanguage();
        String owner = request.getOwner() != null ? request.getOwner() : service.getOwner();
        String team = request.getTeam() != null ? request.getTeam() : service.getTeam();
        String repositoryUrl = request.getRepositoryUrl() != null ? request.getRepositoryUrl() : service.getRepositoryUrl();
        String apiDocUrl = request.getApiDocUrl() != null ? request.getApiDocUrl() : service.getApiDocUrl();
        ServiceStatus status = request.getStatus() != null ? request.getStatus() : service.getStatus();
        String version = request.getVersion() != null ? request.getVersion() : service.getVersion();

        service.update(name, description, language, owner, team, repositoryUrl, apiDocUrl, status, version);

        if (request.getTags() != null) {
            service.getTags().forEach(service::removeTag);
            request.getTags().forEach(service::addTag);
        }

        serviceRepository.save(service);
        return ServiceResponse.fromDomain(service);
    }
}
