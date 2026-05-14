package com.houserental.service;

import com.houserental.HouseRentalApplication;
import com.houserental.builder.TestDataBuilder;
import com.houserental.dto.*;
import com.houserental.entity.House;
import com.houserental.entity.Landlord;
import com.houserental.entity.LeaseApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = HouseRentalApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional
class ApplicationServiceTest {

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private HouseService houseService;

    @Autowired
    private LandlordService landlordService;

    private Landlord testLandlord;
    private House testHouse;

    @BeforeEach
    void setUp() {
        LandlordDTO landlordDTO = TestDataBuilder.buildLandlordDTO();
        testLandlord = landlordService.createLandlord(landlordDTO);

        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        testHouse = houseService.createHouse(houseDTO);
    }

    @Test
    @DisplayName("创建租赁申请成功")
    void testCreateApplication_Success() {
        ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());

        LeaseApplication result = applicationService.createApplication(createDTO);

        assertNotNull(result);
        assertNotNull(result.getApplicationId());
        assertTrue(result.getApplicationId().startsWith("app_"));
        assertEquals(testHouse.getHouseId(), result.getHouseId());
        assertEquals(testLandlord.getLandlordId(), result.getLandlordId());
        assertEquals("pending", result.getApplicationStatus());
        assertNotNull(result.getApplicationTime());
    }

    @Test
    @DisplayName("获取申请信息成功")
    void testGetApplicationById_Success() {
        ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());
        LeaseApplication created = applicationService.createApplication(createDTO);

        LeaseApplication result = applicationService.getApplicationById(created.getApplicationId());

        assertNotNull(result);
        assertEquals(created.getApplicationId(), result.getApplicationId());
    }

    @Test
    @DisplayName("审批通过申请成功")
    void testApproveApplication_Success() {
        ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());
        LeaseApplication created = applicationService.createApplication(createDTO);

        ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(created.getApplicationId());
        LeaseApplication result = applicationService.approveApplication(approveDTO);

        assertEquals("approved", result.getApplicationStatus());
        assertNotNull(result.getApprovedAt());
    }

    @Test
    @DisplayName("审批拒绝申请成功")
    void testRejectApplication_Success() {
        ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());
        LeaseApplication created = applicationService.createApplication(createDTO);

        ApplicationRejectDTO rejectDTO = TestDataBuilder.buildApplicationRejectDTO(created.getApplicationId());
        LeaseApplication result = applicationService.rejectApplication(rejectDTO);

        assertEquals("rejected", result.getApplicationStatus());
        assertNotNull(result.getRejectedAt());
        assertEquals(rejectDTO.getRejectReason(), result.getRejectReason());
    }

    @Test
    @DisplayName("取消申请成功")
    void testCancelApplication_Success() {
        ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());
        LeaseApplication created = applicationService.createApplication(createDTO);

        LeaseApplication result = applicationService.cancelApplication(created.getApplicationId());

        assertEquals("cancelled", result.getApplicationStatus());
    }

    @Test
    @DisplayName("重复审批申请失败")
    void testApproveApplication_AlreadyProcessed() {
        ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());
        LeaseApplication created = applicationService.createApplication(createDTO);

        ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(created.getApplicationId());
        applicationService.approveApplication(approveDTO);

        assertThrows(Exception.class, () -> {
            applicationService.approveApplication(approveDTO);
        });
    }

    @Test
    @DisplayName("房源已出租时创建申请失败")
    void testCreateApplication_HouseRented() {
        houseService.updateHouseStatus(testHouse.getHouseId(), "rented");

        ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());

        assertThrows(Exception.class, () -> {
            applicationService.createApplication(createDTO);
        });
    }

    @Test
    @DisplayName("统计申请数量")
    void testCountApplications() {
        ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(testHouse.getHouseId());
        applicationService.createApplication(createDTO);

        long total = applicationService.countTotalApplications();
        long pending = applicationService.countPendingApplications();

        assertTrue(total > 0);
        assertTrue(pending > 0);
    }
}
