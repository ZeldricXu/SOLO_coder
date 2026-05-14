package com.houserental.builder;

import com.houserental.dto.*;
import com.houserental.entity.*;
import com.houserental.util.IdGenerator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public class TestDataBuilder {

    // ========== DTO 构建方法 ==========

    public static LandlordDTO buildLandlordDTO() {
        LandlordDTO dto = new LandlordDTO();
        dto.setLandlordName("张三");
        dto.setLandlordPhone("13800138001");
        dto.setLandlordStatus("active");
        return dto;
    }

    public static LandlordDTO buildLandlordDTO(String name, String phone) {
        LandlordDTO dto = new LandlordDTO();
        dto.setLandlordName(name);
        dto.setLandlordPhone(phone);
        dto.setLandlordStatus("active");
        return dto;
    }

    public static TenantDTO buildTenantDTO() {
        TenantDTO dto = new TenantDTO();
        dto.setTenantName("李四");
        dto.setTenantPhone("13900139001");
        dto.setTenantIdType("identity");
        dto.setTenantIdNumber("110101199001011234");
        dto.setTenantStatus("active");
        return dto;
    }

    public static TenantDTO buildTenantDTO(String name, String phone) {
        TenantDTO dto = new TenantDTO();
        dto.setTenantName(name);
        dto.setTenantPhone(phone);
        dto.setTenantIdType("identity");
        dto.setTenantIdNumber("11010119900101" + String.format("%04d", (int)(Math.random() * 9999)));
        dto.setTenantStatus("active");
        return dto;
    }

    public static HouseDTO buildHouseDTO(String landlordId) {
        HouseDTO dto = new HouseDTO();
        dto.setLandlordId(landlordId);
        dto.setHouseAddress("北京市朝阳区xxx街道xxx号");
        dto.setHouseType("apartment");
        dto.setHouseArea(80.0);
        dto.setHouseRent(3000.0);
        dto.setHouseFeatures(Arrays.asList("空调", "电视", "洗衣机"));
        return dto;
    }

    public static HouseDTO buildHouseDTO(String landlordId, String address, String type, double area, double rent) {
        HouseDTO dto = new HouseDTO();
        dto.setLandlordId(landlordId);
        dto.setHouseAddress(address);
        dto.setHouseType(type);
        dto.setHouseArea(area);
        dto.setHouseRent(rent);
        dto.setHouseFeatures(Arrays.asList("空调", "电视"));
        return dto;
    }

    public static ApplicationCreateDTO buildApplicationCreateDTO(String houseId) {
        ApplicationCreateDTO dto = new ApplicationCreateDTO();
        dto.setHouseId(houseId);
        dto.setTenantName("李四");
        dto.setTenantPhone("13900139001");
        dto.setTenantIdType("identity");
        dto.setTenantIdNumber("110101199001011234");
        return dto;
    }

    public static ApplicationCreateDTO buildApplicationCreateDTO(String houseId, String tenantName, String tenantPhone) {
        ApplicationCreateDTO dto = new ApplicationCreateDTO();
        dto.setHouseId(houseId);
        dto.setTenantName(tenantName);
        dto.setTenantPhone(tenantPhone);
        dto.setTenantIdType("identity");
        dto.setTenantIdNumber("11010119900101" + String.format("%04d", (int)(Math.random() * 9999)));
        return dto;
    }

    public static ApplicationApproveDTO buildApplicationApproveDTO(String applicationId) {
        ApplicationApproveDTO dto = new ApplicationApproveDTO();
        dto.setApplicationId(applicationId);
        dto.setApproverId("admin_001");
        dto.setContractStart(LocalDate.now());
        dto.setContractEnd(LocalDate.now().plusYears(1));
        dto.setContractRent(3000.0);
        return dto;
    }

    public static ApplicationRejectDTO buildApplicationRejectDTO(String applicationId) {
        ApplicationRejectDTO dto = new ApplicationRejectDTO();
        dto.setApplicationId(applicationId);
        dto.setApproverId("admin_001");
        dto.setRejectReason("房源已被他人预订");
        return dto;
    }

    public static ApplicationRejectDTO buildApplicationRejectDTO(String applicationId, String reason) {
        ApplicationRejectDTO dto = new ApplicationRejectDTO();
        dto.setApplicationId(applicationId);
        dto.setApproverId("admin_001");
        dto.setRejectReason(reason);
        return dto;
    }

    public static ContractRenewDTO buildContractRenewDTO(String contractId) {
        ContractRenewDTO dto = new ContractRenewDTO();
        dto.setContractId(contractId);
        dto.setNewContractStart(LocalDate.now().plusYears(1));
        dto.setNewContractEnd(LocalDate.now().plusYears(2));
        dto.setNewRent(3200.0);
        return dto;
    }

    public static PaymentDTO buildPaymentDTO(String contractId) {
        PaymentDTO dto = new PaymentDTO();
        dto.setContractId(contractId);
        dto.setPaymentAmount(3000.0);
        dto.setPaymentMethod("wechat");
        return dto;
    }

    public static PaymentDTO buildPaymentDTO(String contractId, double amount) {
        PaymentDTO dto = new PaymentDTO();
        dto.setContractId(contractId);
        dto.setPaymentAmount(amount);
        dto.setPaymentMethod("wechat");
        return dto;
    }

    public static HouseStatusDTO buildHouseStatusDTO(String houseId, String status) {
        HouseStatusDTO dto = new HouseStatusDTO();
        dto.setHouseId(houseId);
        dto.setStatus(status);
        return dto;
    }

    // ========== 实体类构建方法 ==========

    public static Landlord buildLandlordEntity() {
        return buildLandlordEntity("张三", "13800138001");
    }

    public static Landlord buildLandlordEntity(String name, String phone) {
        Landlord landlord = new Landlord();
        landlord.setLandlordId(IdGenerator.generateLandlordId());
        landlord.setLandlordName(name);
        landlord.setLandlordPhone(phone);
        landlord.setLandlordStatus("active");
        landlord.setHouseCount(0);
        landlord.setRentedCount(0);
        landlord.setTotalIncome(0.0);
        landlord.setRegisteredAt(LocalDateTime.now());
        return landlord;
    }

    public static Tenant buildTenantEntity() {
        return buildTenantEntity("李四", "13900139001");
    }

    public static Tenant buildTenantEntity(String name, String phone) {
        Tenant tenant = new Tenant();
        tenant.setTenantId(IdGenerator.generateTenantId());
        tenant.setTenantName(name);
        tenant.setTenantPhone(phone);
        tenant.setTenantIdType("identity");
        tenant.setTenantIdNumber("11010119900101" + String.format("%04d", (int)(Math.random() * 9999)));
        tenant.setTenantStatus("active");
        tenant.setApplicationCount(0);
        tenant.setRentedCount(0);
        tenant.setRegisteredAt(LocalDateTime.now());
        return tenant;
    }

    public static House buildHouseEntity(String landlordId) {
        return buildHouseEntity(landlordId, "北京市朝阳区xxx街道xxx号", "apartment", 80.0, 3000.0, "available");
    }

    public static House buildHouseEntity(String landlordId, String status) {
        return buildHouseEntity(landlordId, "北京市朝阳区xxx街道xxx号", "apartment", 80.0, 3000.0, status);
    }

    public static House buildHouseEntity(String landlordId, String address, String type, double area, double rent, String status) {
        House house = new House();
        house.setHouseId(IdGenerator.generateHouseId());
        house.setHouseAddress(address);
        house.setHouseType(type);
        house.setHouseArea(area);
        house.setHouseRent(rent);
        house.setHouseStatus(status);
        house.setHouseFeatures(Arrays.asList("空调", "电视", "洗衣机"));
        house.setLandlordId(landlordId);
        house.setApplicationCount(0);
        house.setCreatedAt(LocalDateTime.now());
        return house;
    }

    public static LeaseApplication buildApplicationEntity(String houseId, String tenantId, String landlordId) {
        return buildApplicationEntity(houseId, tenantId, landlordId, "pending");
    }

    public static LeaseApplication buildApplicationEntity(String houseId, String tenantId, String landlordId, String status) {
        LeaseApplication application = new LeaseApplication();
        application.setApplicationId(IdGenerator.generateApplicationId());
        application.setHouseId(houseId);
        application.setTenantId(tenantId);
        application.setLandlordId(landlordId);
        application.setApplicationStatus(status);
        application.setApplicationTime(LocalDateTime.now());
        application.setCreatedAt(LocalDateTime.now());

        if ("approved".equals(status)) {
            application.setApprovedAt(LocalDateTime.now());
        } else if ("rejected".equals(status)) {
            application.setRejectedAt(LocalDateTime.now());
            application.setRejectReason("测试拒绝原因");
        }

        return application;
    }

    public static Contract buildContractEntity(String houseId, String tenantId, String landlordId) {
        return buildContractEntity(houseId, tenantId, landlordId, 12, "active");
    }

    public static Contract buildContractEntity(String houseId, String tenantId, String landlordId, int months, String status) {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusMonths(months);
        return buildContractEntity(houseId, tenantId, landlordId, start, end, status);
    }

    public static Contract buildContractEntity(String houseId, String tenantId, String landlordId,
                                                LocalDate start, LocalDate end, String status) {
        Contract contract = new Contract();
        contract.setContractId(IdGenerator.generateContractId());
        contract.setHouseId(houseId);
        contract.setTenantId(tenantId);
        contract.setLandlordId(landlordId);
        contract.setContractStart(start);
        contract.setContractEnd(end);
        contract.setContractRent(3000.0);
        contract.setContractStatus(status);
        contract.setRenewalCount(0);
        contract.setSignedAt(LocalDateTime.now());
        contract.setCreatedAt(LocalDateTime.now());
        return contract;
    }

    public static Payment buildPaymentEntity(String contractId, String tenantId) {
        return buildPaymentEntity(contractId, tenantId, 3000.0, "paid");
    }

    public static Payment buildPaymentEntity(String contractId, String tenantId, double amount, String status) {
        Payment payment = new Payment();
        payment.setPaymentId(IdGenerator.generatePaymentId());
        payment.setContractId(contractId);
        payment.setTenantId(tenantId);
        payment.setPaymentAmount(amount);
        payment.setPaymentPeriod(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        payment.setPaymentStatus(status);
        payment.setPaymentMethod("wechat");
        if ("paid".equals(status)) {
            payment.setPaidAt(LocalDateTime.now());
        }
        payment.setCreatedAt(LocalDateTime.now());
        return payment;
    }

    public static Statistics buildStatisticsEntity() {
        Statistics stat = new Statistics();
        stat.setStatId(IdGenerator.generateStatId());
        stat.setStatMonth(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        stat.setHouseCount(10);
        stat.setAvailableHouseCount(6);
        stat.setRentedHouseCount(4);
        stat.setApplicationCount(20);
        stat.setApprovedApplicationCount(15);
        stat.setRejectedApplicationCount(5);
        stat.setContractCount(15);
        stat.setRenewalCount(2);
        stat.setRentAmount(45000.0);
        stat.setLandlordCount(5);
        stat.setTenantCount(15);
        stat.setCreatedAt(LocalDateTime.now());
        return stat;
    }

    public static History buildHistoryEntity() {
        History history = new History();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setHistoryType("application");
        history.setRelatedId("app_test_001");
        history.setRelatedType("application");
        history.setAction("CREATE");
        history.setDescription("测试历史记录");
        history.setHouseId("house_test_001");
        history.setTenantId("tenant_test_001");
        history.setLandlordId("landlord_test_001");
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }

    // ========== 场景化测试数据构建 ==========

    public static class ApplicationTestScenarios {
        public static List<LeaseApplication> buildMultipleApplicationsForSameHouse(String houseId, String landlordId, int count) {
            Tenant tenant1 = buildTenantEntity("租客1", "13900000001");
            Tenant tenant2 = buildTenantEntity("租客2", "13900000002");
            Tenant tenant3 = buildTenantEntity("租客3", "13900000003");

            List<Tenant> tenants = Arrays.asList(tenant1, tenant2, tenant3);

            java.util.List<LeaseApplication> applications = new java.util.ArrayList<>();
            for (int i = 0; i < count && i < tenants.size(); i++) {
                LeaseApplication app = buildApplicationEntity(
                        houseId,
                        tenants.get(i).getTenantId(),
                        landlordId,
                        i == 0 ? "approved" : "pending"
                );
                applications.add(app);
            }
            return applications;
        }

        public static List<LeaseApplication> buildApplicationsForDifferentHouses(String tenantId, String landlordId, int count) {
            java.util.List<LeaseApplication> applications = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                String houseId = "house_scene_" + i;
                LeaseApplication app = buildApplicationEntity(
                        houseId,
                        tenantId,
                        landlordId,
                        "pending"
                );
                applications.add(app);
            }
            return applications;
        }

        public static LeaseApplication buildPendingApplication() {
            return buildApplicationEntity("house_001", "tenant_001", "landlord_001", "pending");
        }

        public static LeaseApplication buildApprovedApplication() {
            return buildApplicationEntity("house_001", "tenant_001", "landlord_001", "approved");
        }

        public static LeaseApplication buildRejectedApplication() {
            return buildApplicationEntity("house_001", "tenant_001", "landlord_001", "rejected");
        }

        public static LeaseApplication buildCancelledApplication() {
            return buildApplicationEntity("house_001", "tenant_001", "landlord_001", "cancelled");
        }
    }

    public static class ContractTestScenarios {
        public static Contract buildLongTermContract(String houseId, String tenantId, String landlordId) {
            return buildContractEntity(houseId, tenantId, landlordId, 24, "active");
        }

        public static Contract buildShortTermContract(String houseId, String tenantId, String landlordId) {
            return buildContractEntity(houseId, tenantId, landlordId, 6, "active");
        }

        public static Contract buildExpiringContract(String houseId, String tenantId, String landlordId, int daysUntilExpiry) {
            LocalDate start = LocalDate.now().minusMonths(11);
            LocalDate end = LocalDate.now().plusDays(daysUntilExpiry);
            return buildContractEntity(houseId, tenantId, landlordId, start, end, "active");
        }

        public static Contract buildActiveContract(String houseId, String tenantId, String landlordId) {
            return buildContractEntity(houseId, tenantId, landlordId, 12, "active");
        }

        public static Contract buildExpiredContract(String houseId, String tenantId, String landlordId) {
            return buildContractEntity(houseId, tenantId, landlordId, -1, "expired");
        }

        public static Contract buildTerminatedContract(String houseId, String tenantId, String landlordId) {
            return buildContractEntity(houseId, tenantId, landlordId, 6, "terminated");
        }

        public static Contract buildRenewedContract(String houseId, String tenantId, String landlordId) {
            Contract contract = buildContractEntity(houseId, tenantId, landlordId, 24, "active");
            contract.setRenewalCount(1);
            contract.setPreviousContractId("contract_prev_001");
            return contract;
        }

        public static List<Contract> buildContractsWithDifferentTypes(String houseId, String tenantId, String landlordId) {
            return Arrays.asList(
                    buildLongTermContract(houseId + "_l", tenantId, landlordId),
                    buildShortTermContract(houseId + "_s", tenantId, landlordId)
            );
        }
    }

    public static class HouseTestScenarios {
        public static House buildAvailableHouse(String landlordId) {
            return buildHouseEntity(landlordId, "available");
        }

        public static House buildRentedHouse(String landlordId) {
            return buildHouseEntity(landlordId, "rented");
        }

        public static House buildOfflineHouse(String landlordId) {
            return buildHouseEntity(landlordId, "offline");
        }

        public static House buildMaintenanceHouse(String landlordId) {
            return buildHouseEntity(landlordId, "maintenance");
        }

        public static List<House> buildHousesWithDifferentStatuses(String landlordId) {
            return Arrays.asList(
                    buildAvailableHouse(landlordId),
                    buildRentedHouse(landlordId),
                    buildOfflineHouse(landlordId)
            );
        }

        public static List<House> buildHousesWithDifferentTypes(String landlordId) {
            return Arrays.asList(
                    buildHouseEntity(landlordId, "北京市朝阳区", "apartment", 80.0, 3000.0, "available"),
                    buildHouseEntity(landlordId, "北京市海淀区", "house", 120.0, 5000.0, "available"),
                    buildHouseEntity(landlordId, "北京市西城区", "villa", 200.0, 10000.0, "available"),
                    buildHouseEntity(landlordId, "北京市东城区", "studio", 40.0, 2000.0, "available")
            );
        }

        public static House buildApartment(String landlordId) {
            return buildHouseEntity(landlordId, "北京市朝阳区", "apartment", 80.0, 3000.0, "available");
        }

        public static House buildHouse(String landlordId) {
            return buildHouseEntity(landlordId, "北京市海淀区", "house", 120.0, 5000.0, "available");
        }

        public static House buildVilla(String landlordId) {
            return buildHouseEntity(landlordId, "北京市西城区", "villa", 200.0, 10000.0, "available");
        }

        public static House buildStudio(String landlordId) {
            return buildHouseEntity(landlordId, "北京市东城区", "studio", 40.0, 2000.0, "available");
        }
    }

    public static class PaymentTestScenarios {
        public static Payment buildPendingPayment(String contractId, String tenantId) {
            return buildPaymentEntity(contractId, tenantId, 3000.0, "pending");
        }

        public static Payment buildPaidPayment(String contractId, String tenantId) {
            return buildPaymentEntity(contractId, tenantId, 3000.0, "paid");
        }

        public static Payment buildFailedPayment(String contractId, String tenantId) {
            return buildPaymentEntity(contractId, tenantId, 3000.0, "failed");
        }

        public static List<Payment> buildMultiplePayments(String contractId, String tenantId, int months) {
            List<Payment> payments = new java.util.ArrayList<>();
            for (int i = 0; i < months; i++) {
                Payment payment = buildPaidPayment(contractId, tenantId);
                payment.setPaymentPeriod(LocalDate.now().minusMonths(i).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
                payments.add(payment);
            }
            return payments;
        }
    }

    public static class SearchTestScenarios {
        public static HouseSearchDTO buildRentRangeSearch(double minRent, double maxRent) {
            HouseSearchDTO dto = new HouseSearchDTO();
            dto.setMinRent(minRent);
            dto.setMaxRent(maxRent);
            return dto;
        }

        public static HouseSearchDTO buildAreaRangeSearch(double minArea, double maxArea) {
            HouseSearchDTO dto = new HouseSearchDTO();
            dto.setMinArea(minArea);
            dto.setMaxArea(maxArea);
            return dto;
        }

        public static HouseSearchDTO buildKeywordSearch(String keyword) {
            HouseSearchDTO dto = new HouseSearchDTO();
            dto.setKeyword(keyword);
            return dto;
        }

        public static HouseSearchDTO buildTypeSearch(String type) {
            HouseSearchDTO dto = new HouseSearchDTO();
            dto.setHouseType(type);
            return dto;
        }

        public static HouseSearchDTO buildCombinedSearch(double minRent, double maxRent, String type) {
            HouseSearchDTO dto = new HouseSearchDTO();
            dto.setMinRent(minRent);
            dto.setMaxRent(maxRent);
            dto.setHouseType(type);
            return dto;
        }
    }
}
