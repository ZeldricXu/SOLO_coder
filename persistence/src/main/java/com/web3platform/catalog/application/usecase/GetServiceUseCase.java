package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.application.dto.ServiceResponse;
import com.web3platform.catalog.domain.exception.ServiceEntryNotFoundException;
import com.web3platform.catalog.domain.repository.ServiceRepository;

import java.util.UUID;

public class GetServiceUseCase {
    private final ServiceRepository serviceRepository;

    public GetServiceUseCase(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    public ServiceResponse execute(UUID serviceId) {
        return serviceRepository.findById(serviceId)
            .map(ServiceResponse::fromDomain)
            .orElseThrow(() -> ServiceEntryNotFoundException.forId(serviceId));
    }
}
