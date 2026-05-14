package com.houserental.service;

import com.houserental.dto.TenantDTO;
import com.houserental.entity.Tenant;
import com.houserental.exception.HouseRentalException;
import com.houserental.repository.TenantRepository;
import com.houserental.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class TenantService {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private StatisticsService statisticsService;

    @Transactional
    public Tenant createTenant(TenantDTO dto) {
        Optional<Tenant> existing = tenantRepository.findByTenantPhone(dto.getTenantPhone());
        if (existing.isPresent()) {
            return existing.get();
        }

        Tenant tenant = new Tenant();
        tenant.setTenantId(IdGenerator.generateTenantId());
        tenant.setTenantName(dto.getTenantName());
        tenant.setTenantPhone(dto.getTenantPhone());
        tenant.setTenantIdType(dto.getTenantIdType() != null ? dto.getTenantIdType() : "identity");
        tenant.setTenantIdNumber(dto.getTenantIdNumber());
        tenant.setTenantStatus(dto.getTenantStatus() != null ? dto.getTenantStatus() : "active");
        tenant.setApplicationCount(0);
        tenant.setRentedCount(0);

        Tenant saved = tenantRepository.save(tenant);
        historyService.recordTenantHistory(saved.getTenantId(), "CREATE",
                "租客信息创建成功：" + saved.getTenantName());
        statisticsService.incrementTenantCount();
        return saved;
    }

    @Transactional
    public Tenant getOrCreateTenant(String tenantName, String tenantPhone, String tenantIdType, String tenantIdNumber) {
        Optional<Tenant> existing = tenantRepository.findByTenantPhone(tenantPhone);
        if (existing.isPresent()) {
            return existing.get();
        }

        Tenant tenant = new Tenant();
        tenant.setTenantId(IdGenerator.generateTenantId());
        tenant.setTenantName(tenantName);
        tenant.setTenantPhone(tenantPhone);
        tenant.setTenantIdType(tenantIdType != null ? tenantIdType : "identity");
        tenant.setTenantIdNumber(tenantIdNumber);
        tenant.setTenantStatus("active");
        tenant.setApplicationCount(0);
        tenant.setRentedCount(0);

        Tenant saved = tenantRepository.save(tenant);
        historyService.recordTenantHistory(saved.getTenantId(), "CREATE",
                "租客信息创建成功：" + saved.getTenantName());
        statisticsService.incrementTenantCount();
        return saved;
    }

    @Transactional
    public Tenant getTenantById(String tenantId) {
        return tenantRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new HouseRentalException(404, "租客不存在: " + tenantId));
    }

    @Transactional
    public Tenant updateTenant(String tenantId, TenantDTO dto) {
        Tenant tenant = getTenantById(tenantId);
        tenant.setTenantName(dto.getTenantName());
        if (dto.getTenantPhone() != null && !dto.getTenantPhone().equals(tenant.getTenantPhone())) {
            Optional<Tenant> existing = tenantRepository.findByTenantPhone(dto.getTenantPhone());
            if (existing.isPresent() && !existing.get().getTenantId().equals(tenantId)) {
                throw new HouseRentalException(400, "该联系方式已被其他租客使用");
            }
            tenant.setTenantPhone(dto.getTenantPhone());
        }
        if (dto.getTenantIdType() != null) {
            tenant.setTenantIdType(dto.getTenantIdType());
        }
        if (dto.getTenantIdNumber() != null) {
            tenant.setTenantIdNumber(dto.getTenantIdNumber());
        }
        if (dto.getTenantStatus() != null) {
            tenant.setTenantStatus(dto.getTenantStatus());
        }
        Tenant saved = tenantRepository.save(tenant);
        historyService.recordTenantHistory(saved.getTenantId(), "UPDATE",
                "租客信息更新成功");
        return saved;
    }

    @Transactional
    public void incrementApplicationCount(String tenantId) {
        Tenant tenant = getTenantById(tenantId);
        tenant.setApplicationCount(tenant.getApplicationCount() + 1);
        tenantRepository.save(tenant);
        historyService.recordTenantHistory(tenantId, "APPLICATION_ADD",
                "提交租赁申请，申请次数：" + (tenant.getApplicationCount()));
    }

    @Transactional
    public void incrementRentedCount(String tenantId) {
        Tenant tenant = getTenantById(tenantId);
        tenant.setRentedCount(tenant.getRentedCount() + 1);
        tenantRepository.save(tenant);
        historyService.recordTenantHistory(tenantId, "RENT_SUCCESS",
                "成功签约租房，签约次数：" + (tenant.getRentedCount()));
    }

    @Transactional
    public void decrementRentedCount(String tenantId) {
        Tenant tenant = getTenantById(tenantId);
        if (tenant.getRentedCount() > 0) {
            tenant.setRentedCount(tenant.getRentedCount() - 1);
            tenantRepository.save(tenant);
            historyService.recordTenantHistory(tenantId, "RENT_END",
                    "租约结束，当前签约数：" + (tenant.getRentedCount()));
        }
    }

    public List<Tenant> getAllTenants() {
        return tenantRepository.findAll();
    }

    public List<Tenant> getActiveTenants() {
        return tenantRepository.findByTenantStatus("active");
    }

    public long countTotalTenants() {
        return tenantRepository.countTotalTenants();
    }

    public long countActiveTenants() {
        return tenantRepository.countByStatus("active");
    }
}
