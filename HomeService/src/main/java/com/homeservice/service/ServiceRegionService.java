package com.homeservice.service;

import com.homeservice.dto.ServiceRegionRequest;
import com.homeservice.entity.ServiceRegion;
import com.homeservice.exception.BusinessException;
import com.homeservice.exception.ResourceNotFoundException;
import com.homeservice.repository.ServiceRegionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ServiceRegionService {

    @Autowired
    private ServiceRegionRepository serviceRegionRepository;

    public ServiceRegion createServiceRegion(ServiceRegionRequest request) {
        if (serviceRegionRepository.existsByRegionCode(request.getRegionCode())) {
            throw new BusinessException("Service region code already exists");
        }
        ServiceRegion region = new ServiceRegion(
            request.getRegionCode(),
            request.getRegionName(),
            request.getProvince(),
            request.getCity(),
            request.getDistrict()
        );
        return serviceRegionRepository.save(region);
    }

    public List<ServiceRegion> getAllServiceRegions() {
        return serviceRegionRepository.findAll();
    }

    public List<ServiceRegion> getActiveServiceRegions() {
        return serviceRegionRepository.findByIsActiveTrue();
    }

    public ServiceRegion getServiceRegionByCode(String regionCode) {
        return serviceRegionRepository.findByRegionCode(regionCode)
            .orElseThrow(() -> new ResourceNotFoundException("Service region not found: " + regionCode));
    }

    public ServiceRegion updateServiceRegion(String regionCode, ServiceRegionRequest request) {
        ServiceRegion region = getServiceRegionByCode(regionCode);
        region.setRegionName(request.getRegionName());
        region.setProvince(request.getProvince());
        region.setCity(request.getCity());
        region.setDistrict(request.getDistrict());
        return serviceRegionRepository.save(region);
    }

    public void deleteServiceRegion(String regionCode) {
        ServiceRegion region = getServiceRegionByCode(regionCode);
        serviceRegionRepository.delete(region);
    }

    public ServiceRegion activateServiceRegion(String regionCode) {
        ServiceRegion region = getServiceRegionByCode(regionCode);
        region.setIsActive(true);
        return serviceRegionRepository.save(region);
    }

    public ServiceRegion deactivateServiceRegion(String regionCode) {
        ServiceRegion region = getServiceRegionByCode(regionCode);
        region.setIsActive(false);
        return serviceRegionRepository.save(region);
    }
}
