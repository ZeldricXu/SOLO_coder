package com.houserental.service;

import com.houserental.HouseRentalApplication;
import com.houserental.builder.TestDataBuilder;
import com.houserental.dto.*;
import com.houserental.entity.House;
import com.houserental.entity.Landlord;
import com.houserental.entity.LeaseApplication;
import com.houserental.exception.HouseRentalException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = HouseRentalApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional
@DisplayName("申请模块单元测试")
class ApplicationModuleTest {

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private HouseService houseService;

    @Autowired
    private LandlordService landlordService;

    private Landlord testLandlord;
    private House availableHouse;
    private House rentedHouse;
    private House offlineHouse;

    @BeforeEach
    void setUp() {
        LandlordDTO landlordDTO = TestDataBuilder.buildLandlordDTO();
        testLandlord = landlordService.createLandlord(landlordDTO);

        HouseDTO availableHouseDTO = TestDataBuilder.buildHouseDTO(
                testLandlord.getLandlordId(),
                "北京市朝阳区可用房源",
                "apartment", 80.0, 3000.0
        );
        availableHouse = houseService.createHouse(availableHouseDTO);

        HouseDTO rentedHouseDTO = TestDataBuilder.buildHouseDTO(
                testLandlord.getLandlordId(),
                "北京市朝阳区已租房源",
                "apartment", 75.0, 2800.0
        );
        rentedHouse = houseService.createHouse(rentedHouseDTO);
        houseService.updateHouseStatus(rentedHouse.getHouseId(), "rented");

        HouseDTO offlineHouseDTO = TestDataBuilder.buildHouseDTO(
                testLandlord.getLandlordId(),
                "北京市朝阳区已下架房源",
                "apartment", 90.0, 3500.0
        );
        offlineHouse = houseService.createHouse(offlineHouseDTO);
        houseService.updateHouseStatus(offlineHouse.getHouseId(), "offline");
    }

    @Nested
    @DisplayName("申请检查机制测试")
    class ApplicationCheckTests {

        @Test
        @DisplayName("创建申请成功 - 房源可用时")
        void testCreateApplication_Success_WhenHouseAvailable() {
            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId()
            );

            LeaseApplication result = applicationService.createApplication(createDTO);

            assertNotNull(result);
            assertNotNull(result.getApplicationId());
            assertTrue(result.getApplicationId().startsWith("app_"));
            assertEquals(availableHouse.getHouseId(), result.getHouseId());
            assertEquals(testLandlord.getLandlordId(), result.getLandlordId());
            assertEquals("pending", result.getApplicationStatus());
            assertNotNull(result.getApplicationTime());
        }

        @Test
        @DisplayName("创建申请失败 - 房源已出租时")
        void testCreateApplication_Fail_WhenHouseRented() {
            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    rentedHouse.getHouseId()
            );

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> applicationService.createApplication(createDTO)
            );

            assertTrue(exception.getMessage().contains("房源已出租"));
            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("创建申请失败 - 房源已下架时")
        void testCreateApplication_Fail_WhenHouseOffline() {
            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    offlineHouse.getHouseId()
            );

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> applicationService.createApplication(createDTO)
            );

            assertTrue(exception.getMessage().contains("房源已下架"));
            assertEquals(400, exception.getCode());
        }

        @Test
        @DisplayName("创建申请失败 - 房源不存在时")
        void testCreateApplication_Fail_WhenHouseNotExist() {
            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    "non_existent_house"
            );

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> applicationService.createApplication(createDTO)
            );

            assertTrue(exception.getMessage().contains("房源不存在"));
            assertEquals(404, exception.getCode());
        }

        @Test
        @DisplayName("重复申请检查 - 同一租客可对不同房源发起申请")
        void testCheckMultipleApplications_DifferentHouses() {
            LandlordDTO landlord2DTO = TestDataBuilder.buildLandlordDTO("房东2", "13800138002");
            Landlord landlord2 = landlordService.createLandlord(landlord2DTO);

            HouseDTO house2DTO = TestDataBuilder.buildHouseDTO(
                    landlord2.getLandlordId(),
                    "北京市海淀区房源",
                    "house", 120.0, 5000.0
            );
            House house2 = houseService.createHouse(house2DTO);

            ApplicationCreateDTO app1DTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId(),
                    "租客李四",
                    "13900139001"
            );
            LeaseApplication app1 = applicationService.createApplication(app1DTO);

            ApplicationCreateDTO app2DTO = TestDataBuilder.buildApplicationCreateDTO(
                    house2.getHouseId(),
                    "租客李四",
                    "13900139001"
            );
            LeaseApplication app2 = applicationService.createApplication(app2DTO);

            assertNotNull(app1);
            assertNotNull(app2);
            assertNotEquals(app1.getApplicationId(), app2.getApplicationId());
            assertNotEquals(app1.getHouseId(), app2.getHouseId());
            assertEquals(app1.getTenantId(), app2.getTenantId());
            assertEquals("pending", app1.getApplicationStatus());
            assertEquals("pending", app2.getApplicationStatus());
        }

        @Test
        @DisplayName("多租客可同时申请同一房源")
        void testCheckMultipleApplications_SameHouse() {
            ApplicationCreateDTO app1DTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId(),
                    "租客1",
                    "13900000001"
            );
            LeaseApplication app1 = applicationService.createApplication(app1DTO);

            ApplicationCreateDTO app2DTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId(),
                    "租客2",
                    "13900000002"
            );
            LeaseApplication app2 = applicationService.createApplication(app2DTO);

            ApplicationCreateDTO app3DTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId(),
                    "租客3",
                    "13900000003"
            );
            LeaseApplication app3 = applicationService.createApplication(app3DTO);

            assertNotNull(app1);
            assertNotNull(app2);
            assertNotNull(app3);
            assertEquals(availableHouse.getHouseId(), app1.getHouseId());
            assertEquals(availableHouse.getHouseId(), app2.getHouseId());
            assertEquals(availableHouse.getHouseId(), app3.getHouseId());
            assertNotEquals(app1.getTenantId(), app2.getTenantId());
            assertNotEquals(app2.getTenantId(), app3.getTenantId());
        }
    }

    @Nested
    @DisplayName("申请状态流转测试")
    class ApplicationStatusFlowTests {

        private LeaseApplication testApplication;

        @BeforeEach
        void setUpApplication() {
            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId()
            );
            testApplication = applicationService.createApplication(createDTO);
        }

        @Test
        @DisplayName("状态流转: pending -> approved")
        void testStatusFlow_PendingToApproved() {
            assertEquals("pending", testApplication.getApplicationStatus());

            ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(
                    testApplication.getApplicationId()
            );
            LeaseApplication approved = applicationService.approveApplication(approveDTO);

            assertEquals("approved", approved.getApplicationStatus());
            assertNotNull(approved.getApprovedAt());
            assertNull(approved.getRejectedAt());
        }

        @Test
        @DisplayName("状态流转: pending -> rejected")
        void testStatusFlow_PendingToRejected() {
            assertEquals("pending", testApplication.getApplicationStatus());

            ApplicationRejectDTO rejectDTO = TestDataBuilder.buildApplicationRejectDTO(
                    testApplication.getApplicationId(),
                    "房东选择了其他租客"
            );
            LeaseApplication rejected = applicationService.rejectApplication(rejectDTO);

            assertEquals("rejected", rejected.getApplicationStatus());
            assertNotNull(rejected.getRejectedAt());
            assertEquals("房东选择了其他租客", rejected.getRejectReason());
            assertNull(rejected.getApprovedAt());
        }

        @Test
        @DisplayName("状态流转: pending -> cancelled")
        void testStatusFlow_PendingToCancelled() {
            assertEquals("pending", testApplication.getApplicationStatus());

            LeaseApplication cancelled = applicationService.cancelApplication(
                    testApplication.getApplicationId()
            );

            assertEquals("cancelled", cancelled.getApplicationStatus());
        }

        @Test
        @DisplayName("重复审批失败 - 已通过的申请无法再次处理")
        void testDuplicateApproval_Fail_WhenAlreadyApproved() {
            ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(
                    testApplication.getApplicationId()
            );
            applicationService.approveApplication(approveDTO);

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> applicationService.approveApplication(approveDTO)
            );

            assertTrue(exception.getMessage().contains("申请已处理"));
        }

        @Test
        @DisplayName("重复审批失败 - 已拒绝的申请无法再次处理")
        void testDuplicateApproval_Fail_WhenAlreadyRejected() {
            ApplicationRejectDTO rejectDTO = TestDataBuilder.buildApplicationRejectDTO(
                    testApplication.getApplicationId()
            );
            applicationService.rejectApplication(rejectDTO);

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> applicationService.approveApplication(
                            TestDataBuilder.buildApplicationApproveDTO(testApplication.getApplicationId())
                    )
            );

            assertTrue(exception.getMessage().contains("申请已处理"));
        }

        @Test
        @DisplayName("审批通过后自动创建合同")
        void testApproval_CreatesContractAutomatically() {
            ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(
                    testApplication.getApplicationId()
            );

            LeaseApplication approved = applicationService.approveApplication(approveDTO);

            assertEquals("approved", approved.getApplicationStatus());

            House updatedHouse = houseService.getHouseById(availableHouse.getHouseId());
            assertEquals("rented", updatedHouse.getHouseStatus());
        }

        @Test
        @DisplayName("完整申请生命周期测试")
        void testCompleteApplicationLifecycle() {
            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId()
            );
            LeaseApplication app = applicationService.createApplication(createDTO);
            assertEquals("pending", app.getApplicationStatus());

            List<LeaseApplication> pendingApps = applicationService.getApplicationsByStatus("pending");
            assertTrue(pendingApps.stream().anyMatch(a -> a.getApplicationId().equals(app.getApplicationId())));

            ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(app.getApplicationId());
            LeaseApplication approved = applicationService.approveApplication(approveDTO);
            assertEquals("approved", approved.getApplicationStatus());

            List<LeaseApplication> approvedApps = applicationService.getApplicationsByStatus("approved");
            assertTrue(approvedApps.stream().anyMatch(a -> a.getApplicationId().equals(app.getApplicationId())));

            long totalCount = applicationService.countTotalApplications();
            long approvedCount = applicationService.countApprovedApplications();
            assertTrue(totalCount > 0);
            assertTrue(approvedCount > 0);
        }
    }

    @Nested
    @DisplayName("房源状态异常处理测试")
    class HouseStatusExceptionTests {

        @Test
        @DisplayName("房源状态为rented时拒绝申请")
        void testRejectApplication_WhenHouseRented() {
            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    rentedHouse.getHouseId()
            );

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> applicationService.createApplication(createDTO)
            );

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("已出租"));
        }

        @Test
        @DisplayName("房源状态为offline时拒绝申请")
        void testRejectApplication_WhenHouseOffline() {
            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    offlineHouse.getHouseId()
            );

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> applicationService.createApplication(createDTO)
            );

            assertEquals(400, exception.getCode());
            assertTrue(exception.getMessage().contains("已下架"));
        }

        @Test
        @DisplayName("房源状态为available时允许申请")
        void testAllowApplication_WhenHouseAvailable() {
            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId()
            );

            LeaseApplication app = applicationService.createApplication(createDTO);

            assertNotNull(app);
            assertEquals("pending", app.getApplicationStatus());
        }

        @Test
        @DisplayName("申请统计更新验证")
        void testApplicationStatistics_UpdatedCorrectly() {
            long beforeTotal = applicationService.countTotalApplications();
            long beforePending = applicationService.countPendingApplications();

            ApplicationCreateDTO createDTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId()
            );
            LeaseApplication app = applicationService.createApplication(createDTO);

            long afterTotal = applicationService.countTotalApplications();
            long afterPending = applicationService.countPendingApplications();

            assertEquals(beforeTotal + 1, afterTotal);
            assertEquals(beforePending + 1, afterPending);

            ApplicationApproveDTO approveDTO = TestDataBuilder.buildApplicationApproveDTO(app.getApplicationId());
            applicationService.approveApplication(approveDTO);

            long afterApproved = applicationService.countApprovedApplications();
            assertTrue(afterApproved > 0);
        }

        @Test
        @DisplayName("按房源查询申请")
        void testGetApplicationsByHouse() {
            ApplicationCreateDTO app1DTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId(),
                    "租客1",
                    "13900000001"
            );
            applicationService.createApplication(app1DTO);

            ApplicationCreateDTO app2DTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId(),
                    "租客2",
                    "13900000002"
            );
            applicationService.createApplication(app2DTO);

            List<LeaseApplication> houseApps = applicationService.getApplicationsByHouse(
                    availableHouse.getHouseId()
            );

            assertEquals(2, houseApps.size());
            assertTrue(houseApps.stream().allMatch(a -> a.getHouseId().equals(availableHouse.getHouseId())));
        }

        @Test
        @DisplayName("按房东查询待审批申请")
        void testGetPendingApplicationsByLandlord() {
            ApplicationCreateDTO appDTO = TestDataBuilder.buildApplicationCreateDTO(
                    availableHouse.getHouseId()
            );
            applicationService.createApplication(appDTO);

            List<LeaseApplication> pendingApps = applicationService.getPendingApplicationsByLandlord(
                    testLandlord.getLandlordId()
            );

            assertFalse(pendingApps.isEmpty());
            assertTrue(pendingApps.stream().allMatch(a -> a.getApplicationStatus().equals("pending")));
            assertTrue(pendingApps.stream().allMatch(a -> a.getLandlordId().equals(testLandlord.getLandlordId())));
        }
    }
}
