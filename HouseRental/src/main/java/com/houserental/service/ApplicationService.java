package com.houserental.service;

import com.houserental.dto.ApplicationApproveDTO;
import com.houserental.dto.ApplicationCreateDTO;
import com.houserental.dto.ApplicationRejectDTO;
import com.houserental.entity.House;
import com.houserental.entity.LeaseApplication;
import com.houserental.entity.Tenant;
import com.houserental.exception.HouseRentalException;
import com.houserental.repository.ApplicationRepository;
import com.houserental.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApplicationService {

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private HouseService houseService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private LandlordService landlordService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private ApplicationCheckService applicationCheckService;

    @Transactional
    public LeaseApplication createApplication(ApplicationCreateDTO dto) {
        House house = houseService.getHouseById(dto.getHouseId());

        Tenant tenant = tenantService.getOrCreateTenant(
                dto.getTenantName(),
                dto.getTenantPhone(),
                dto.getTenantIdType(),
                dto.getTenantIdNumber()
        );

        applicationCheckService.validateApplication(dto.getHouseId(), tenant.getTenantId());

        LeaseApplication application = new LeaseApplication();
        application.setApplicationId(IdGenerator.generateApplicationId());
        application.setHouseId(dto.getHouseId());
        application.setTenantId(tenant.getTenantId());
        application.setLandlordId(house.getLandlordId());
        application.setApplicationStatus("pending");
        application.setApplicationTime(LocalDateTime.now());

        LeaseApplication saved = applicationRepository.save(application);

        houseService.incrementApplicationCount(dto.getHouseId());
        tenantService.incrementApplicationCount(tenant.getTenantId());
        statisticsService.incrementApplicationCount();

        historyService.recordApplicationHistory(
                saved.getApplicationId(),
                "CREATE",
                "租赁申请创建成功，房源：" + house.getHouseAddress(),
                house.getHouseId(),
                tenant.getTenantId(),
                house.getLandlordId()
        );

        landlordService.notifyLandlord(
                house.getLandlordId(),
                "收到新的租赁申请，申请ID：" + saved.getApplicationId()
        );

        return saved;
    }

    @Transactional
    public LeaseApplication getApplicationById(String applicationId) {
        return applicationRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new HouseRentalException(404, "申请不存在: " + applicationId));
    }

    @Transactional
    public LeaseApplication approveApplication(ApplicationApproveDTO dto) {
        LeaseApplication application = getApplicationById(dto.getApplicationId());

        if (!"pending".equals(application.getApplicationStatus())) {
            throw new HouseRentalException(400, "申请已处理，无法重复审批");
        }

        House house = houseService.getHouseById(application.getHouseId());
        houseService.validateHouseAvailable(application.getHouseId());

        application.setApplicationStatus("approved");
        application.setApprovedAt(LocalDateTime.now());
        LeaseApplication saved = applicationRepository.save(application);

        statisticsService.incrementApprovedApplicationCount();

        historyService.recordApplicationHistory(
                saved.getApplicationId(),
                "APPROVE",
                "租赁申请审批通过",
                house.getHouseId(),
                application.getTenantId(),
                application.getLandlordId()
        );

        contractService.createContractFromApplication(
                saved.getApplicationId(),
                house.getHouseId(),
                application.getTenantId(),
                application.getLandlordId(),
                house.getHouseRent(),
                dto.getContractStart(),
                dto.getContractEnd(),
                dto.getContractRent()
        );

        tenantService.incrementRentedCount(application.getTenantId());
        landlordService.incrementRentedCount(application.getLandlordId());

        return saved;
    }

    @Transactional
    public LeaseApplication rejectApplication(ApplicationRejectDTO dto) {
        LeaseApplication application = getApplicationById(dto.getApplicationId());

        if (!"pending".equals(application.getApplicationStatus())) {
            throw new HouseRentalException(400, "申请已处理，无法重复审批");
        }

        application.setApplicationStatus("rejected");
        application.setRejectReason(dto.getRejectReason());
        application.setRejectedAt(LocalDateTime.now());
        LeaseApplication saved = applicationRepository.save(application);

        statisticsService.incrementRejectedApplicationCount();

        historyService.recordApplicationHistory(
                saved.getApplicationId(),
                "REJECT",
                "租赁申请被拒绝，原因：" + (dto.getRejectReason() != null ? dto.getRejectReason() : "无"),
                application.getHouseId(),
                application.getTenantId(),
                application.getLandlordId()
        );

        return saved;
    }

    @Transactional
    public LeaseApplication cancelApplication(String applicationId) {
        LeaseApplication application = getApplicationById(applicationId);

        if (!"pending".equals(application.getApplicationStatus())) {
            throw new HouseRentalException(400, "申请已处理，无法取消");
        }

        application.setApplicationStatus("cancelled");
        LeaseApplication saved = applicationRepository.save(application);

        historyService.recordApplicationHistory(
                saved.getApplicationId(),
                "CANCEL",
                "租赁申请已取消",
                application.getHouseId(),
                application.getTenantId(),
                application.getLandlordId()
        );

        return saved;
    }

    public List<LeaseApplication> getAllApplications() {
        return applicationRepository.findAll();
    }

    public List<LeaseApplication> getApplicationsByStatus(String status) {
        return applicationRepository.findByApplicationStatus(status);
    }

    public List<LeaseApplication> getApplicationsByHouse(String houseId) {
        return applicationRepository.findByHouseId(houseId);
    }

    public List<LeaseApplication> getApplicationsByTenant(String tenantId) {
        return applicationRepository.findByTenantId(tenantId);
    }

    public List<LeaseApplication> getApplicationsByLandlord(String landlordId) {
        return applicationRepository.findByLandlordId(landlordId);
    }

    public List<LeaseApplication> getPendingApplicationsByLandlord(String landlordId) {
        return applicationRepository.findByLandlordIdAndApplicationStatus(landlordId, "pending");
    }

    public long countTotalApplications() {
        return applicationRepository.countTotalApplications();
    }

    public long countApplicationsByStatus(String status) {
        return applicationRepository.countByStatus(status);
    }

    public long countPendingApplications() {
        return applicationRepository.countByStatus("pending");
    }

    public long countApprovedApplications() {
        return applicationRepository.countByStatus("approved");
    }

    public long countRejectedApplications() {
        return applicationRepository.countByStatus("rejected");
    }
}
