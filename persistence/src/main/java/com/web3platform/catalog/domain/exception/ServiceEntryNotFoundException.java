package com.web3platform.catalog.domain.exception;

public class ServiceEntryNotFoundException extends CatalogException {
    public ServiceEntryNotFoundException(String message) {
        super(message);
    }

    public static ServiceEntryNotFoundException forId(java.util.UUID id) {
        return new ServiceEntryNotFoundException("Service entry not found: " + id);
    }
}
