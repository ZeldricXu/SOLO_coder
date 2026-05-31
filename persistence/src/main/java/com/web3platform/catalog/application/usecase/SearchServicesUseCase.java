package com.web3platform.catalog.application.usecase;

import com.web3platform.catalog.application.dto.PagedResult;
import com.web3platform.catalog.application.dto.ServiceResponse;
import com.web3platform.catalog.application.dto.ServiceSearchRequest;
import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.repository.ServiceRepository;
import com.web3platform.catalog.domain.specification.ServiceSpecification;

import java.util.List;
import java.util.stream.Collectors;

public class SearchServicesUseCase {
    private final ServiceRepository serviceRepository;

    public SearchServicesUseCase(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    public PagedResult<ServiceResponse> execute(ServiceSearchRequest request) {
        List<ServiceEntry> allServices = serviceRepository.findAll();
        List<ServiceEntry> filtered = applySpecifications(allServices, request);
        
        int pageSize = Math.max(1, request.getPageSize());
        int page = Math.max(0, request.getPage());
        int start = page * pageSize;
        
        List<ServiceResponse> pageItems = filtered.stream()
            .skip(start)
            .limit(pageSize)
            .map(ServiceResponse::fromDomain)
            .collect(Collectors.toList());

        return new PagedResult<>(pageItems, filtered.size(), page, pageSize);
    }

    private List<ServiceEntry> applySpecifications(List<ServiceEntry> services, ServiceSearchRequest request) {
        ServiceSpecification spec = null;

        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            spec = ServiceSpecification.withKeyword(request.getKeyword());
        }
        if (request.getLanguage() != null && !request.getLanguage().isBlank()) {
            spec = (spec == null) 
                ? ServiceSpecification.withLanguage(request.getLanguage())
                : spec.and(ServiceSpecification.withLanguage(request.getLanguage()));
        }
        if (request.getTeam() != null && !request.getTeam().isBlank()) {
            spec = (spec == null)
                ? ServiceSpecification.withTeam(request.getTeam())
                : spec.and(ServiceSpecification.withTeam(request.getTeam()));
        }
        if (request.getStatus() != null) {
            spec = (spec == null)
                ? ServiceSpecification.withStatus(request.getStatus())
                : spec.and(ServiceSpecification.withStatus(request.getStatus()));
        }
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            spec = (spec == null)
                ? ServiceSpecification.withTags(request.getTags())
                : spec.and(ServiceSpecification.withTags(request.getTags()));
        }

        if (spec == null) {
            return services;
        }

        return services.stream()
            .filter(spec)
            .collect(Collectors.toList());
    }
}
