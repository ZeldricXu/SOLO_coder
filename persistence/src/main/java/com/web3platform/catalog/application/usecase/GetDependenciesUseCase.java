package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.application.dto.DependencyResponse;
import com.web3platform.catalog.domain.repository.DependencyRepository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class GetDependenciesUseCase {
    private final DependencyRepository dependencyRepository;

    public GetDependenciesUseCase(DependencyRepository dependencyRepository) {
        this.dependencyRepository = dependencyRepository;
    }

    public List<DependencyResponse> getDependencies(UUID serviceId) {
        return dependencyRepository.findDependenciesOf(serviceId).stream()
            .map(DependencyResponse::fromDomain)
            .collect(Collectors.toList());
    }

    public List<DependencyResponse> getDependents(UUID serviceId) {
        return dependencyRepository.findDependentsOf(serviceId).stream()
            .map(DependencyResponse::fromDomain)
            .collect(Collectors.toList());
    }
}
