package com.homeservice.service;

import com.homeservice.config.ServiceTypeProperties;
import com.homeservice.config.ServiceTypeProperties.ServiceTypeConfig;
import com.homeservice.dto.ServiceTypeRequest;
import com.homeservice.entity.ServiceType;
import com.homeservice.exception.BusinessException;
import com.homeservice.exception.ResourceNotFoundException;
import com.homeservice.repository.ServiceTypeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ServiceTypeService {

    private static final Logger logger = LoggerFactory.getLogger(ServiceTypeService.class);

    @Autowired
    private ServiceTypeRepository serviceTypeRepository;

    @Autowired
    private ServiceTypeProperties serviceTypeProperties;

    @PostConstruct
    public void init() {
        if (serviceTypeProperties.isAutoCreateFromConfig()) {
            logger.info("Auto-creating service types from configuration");
            createServiceTypesFromConfig();
        }
    }

    public void createServiceTypesFromConfig() {
        List<ServiceTypeConfig> configs = serviceTypeProperties.getDefaults();
        if (configs == null || configs.isEmpty()) {
            logger.info("No default service types configured");
            return;
        }

        int created = 0;
        int skipped = 0;

        for (ServiceTypeConfig config : configs) {
            if (serviceTypeRepository.existsByTypeCode(config.getCode())) {
                logger.debug("Service type {} already exists, skipping", config.getCode());
                skipped++;
                continue;
            }

            ServiceType serviceType = new ServiceType(
                config.getCode(),
                config.getName(),
                config.getDescription(),
                config.getBasePrice()
            );
            serviceType.setIsActive(config.isActive());
            
            if (config.getSupportedRegions() != null && !config.getSupportedRegions().isEmpty()) {
                serviceType.setSupportedRegions(String.join(",", config.getSupportedRegions()));
            }
            if (config.getMinDuration() != null) {
                serviceType.setMinDuration(config.getMinDuration());
            }
            if (config.getMaxDuration() != null) {
                serviceType.setMaxDuration(config.getMaxDuration());
            }

            serviceTypeRepository.save(serviceType);
            logger.info("Created service type from config: {} - {}", config.getCode(), config.getName());
            created++;
        }

        logger.info("Service type initialization complete: created={}, skipped={}", created, skipped);
    }

    public ServiceType createServiceType(ServiceTypeRequest request) {
        if (serviceTypeRepository.existsByTypeCode(request.getTypeCode())) {
            throw new BusinessException("Service type code already exists");
        }
        ServiceType serviceType = new ServiceType(
            request.getTypeCode(),
            request.getTypeName(),
            request.getDescription(),
            request.getBasePrice()
        );
        return serviceTypeRepository.save(serviceType);
    }

    public ServiceType createServiceTypeFromConfig(ServiceTypeConfig config) {
        if (serviceTypeRepository.existsByTypeCode(config.getCode())) {
            throw new BusinessException("Service type code already exists: " + config.getCode());
        }

        ServiceType serviceType = new ServiceType(
            config.getCode(),
            config.getName(),
            config.getDescription(),
            config.getBasePrice()
        );
        serviceType.setIsActive(config.isActive());
        
        if (config.getSupportedRegions() != null && !config.getSupportedRegions().isEmpty()) {
            serviceType.setSupportedRegions(String.join(",", config.getSupportedRegions()));
        }
        if (config.getMinDuration() != null) {
            serviceType.setMinDuration(config.getMinDuration());
        }
        if (config.getMaxDuration() != null) {
            serviceType.setMaxDuration(config.getMaxDuration());
        }

        logger.info("Creating service type from config: {}", config.getCode());
        return serviceTypeRepository.save(serviceType);
    }

    public List<ServiceType> getAllServiceTypes() {
        return serviceTypeRepository.findAll();
    }

    public List<ServiceType> getActiveServiceTypes() {
        return serviceTypeRepository.findByIsActiveTrue();
    }

    public ServiceType getServiceTypeByCode(String typeCode) {
        return serviceTypeRepository.findByTypeCode(typeCode)
            .orElseThrow(() -> new ResourceNotFoundException("Service type not found: " + typeCode));
    }

    public boolean serviceTypeExists(String typeCode) {
        return serviceTypeRepository.existsByTypeCode(typeCode);
    }

    public ServiceType updateServiceType(String typeCode, ServiceTypeRequest request) {
        ServiceType serviceType = getServiceTypeByCode(typeCode);
        serviceType.setTypeName(request.getTypeName());
        serviceType.setDescription(request.getDescription());
        serviceType.setBasePrice(request.getBasePrice());
        return serviceTypeRepository.save(serviceType);
    }

    public ServiceType updateServiceTypeFromConfig(String typeCode, ServiceTypeConfig config) {
        if (!typeCode.equals(config.getCode())) {
            throw new BusinessException("Type code mismatch: " + typeCode + " vs " + config.getCode());
        }

        ServiceType serviceType = getServiceTypeByCode(typeCode);
        serviceType.setTypeName(config.getName());
        serviceType.setDescription(config.getDescription());
        serviceType.setBasePrice(config.getBasePrice());
        serviceType.setIsActive(config.isActive());
        
        if (config.getSupportedRegions() != null && !config.getSupportedRegions().isEmpty()) {
            serviceType.setSupportedRegions(String.join(",", config.getSupportedRegions()));
        }
        if (config.getMinDuration() != null) {
            serviceType.setMinDuration(config.getMinDuration());
        }
        if (config.getMaxDuration() != null) {
            serviceType.setMaxDuration(config.getMaxDuration());
        }

        logger.info("Updating service type from config: {}", typeCode);
        return serviceTypeRepository.save(serviceType);
    }

    public ServiceType createOrUpdateFromConfig(ServiceTypeConfig config) {
        if (serviceTypeRepository.existsByTypeCode(config.getCode())) {
            return updateServiceTypeFromConfig(config.getCode(), config);
        } else {
            return createServiceTypeFromConfig(config);
        }
    }

    public void deleteServiceType(String typeCode) {
        ServiceType serviceType = getServiceTypeByCode(typeCode);
        serviceTypeRepository.delete(serviceType);
    }

    public ServiceType activateServiceType(String typeCode) {
        ServiceType serviceType = getServiceTypeByCode(typeCode);
        serviceType.setIsActive(true);
        return serviceTypeRepository.save(serviceType);
    }

    public ServiceType deactivateServiceType(String typeCode) {
        ServiceType serviceType = getServiceTypeByCode(typeCode);
        serviceType.setIsActive(false);
        return serviceTypeRepository.save(serviceType);
    }

    public List<ServiceType> getServiceTypesByRegion(String regionCode) {
        return serviceTypeRepository.findBySupportedRegionsContaining(regionCode);
    }

    public List<ServiceTypeConfig> getConfiguredServiceTypes() {
        return serviceTypeProperties.getDefaults();
    }

    public void reloadServiceTypesFromConfig() {
        logger.info("Reloading service types from configuration");
        List<ServiceTypeConfig> configs = serviceTypeProperties.getDefaults();
        if (configs != null) {
            for (ServiceTypeConfig config : configs) {
                createOrUpdateFromConfig(config);
            }
        }
    }
}
