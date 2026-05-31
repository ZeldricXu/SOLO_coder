package com.web3platform.catalog.domain.specification;

import com.web3platform.catalog.domain.model.ServiceEntry;
import com.web3platform.catalog.domain.model.ServiceStatus;

import java.util.List;
import java.util.function.Predicate;

public interface ServiceSpecification extends Predicate<ServiceEntry> {
    static ServiceSpecification withKeyword(String keyword) {
        return service -> 
            service.getName().toLowerCase().contains(keyword.toLowerCase()) ||
            service.getDescription().toLowerCase().contains(keyword.toLowerCase());
    }

    static ServiceSpecification withLanguage(String language) {
        return service -> service.getLanguage().equalsIgnoreCase(language);
    }

    static ServiceSpecification withTeam(String team) {
        return service -> service.getTeam().equalsIgnoreCase(team);
    }

    static ServiceSpecification withStatus(ServiceStatus status) {
        return service -> service.getStatus() == status;
    }

    static ServiceSpecification withTags(List<String> tags) {
        return service -> service.getTags().containsAll(tags);
    }

    default ServiceSpecification and(ServiceSpecification other) {
        return service -> this.test(service) && other.test(service);
    }
}
