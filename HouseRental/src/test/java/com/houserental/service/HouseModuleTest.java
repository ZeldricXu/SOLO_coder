package com.houserental.service;

import com.houserental.HouseRentalApplication;
import com.houserental.builder.TestDataBuilder;
import com.houserental.dto.*;
import com.houserental.entity.House;
import com.houserental.entity.Landlord;
import com.houserental.exception.HouseRentalException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = HouseRentalApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional
@DisplayName("房源管理模块单元测试")
class HouseModuleTest {

    @Autowired
    private HouseService houseService;

    @Autowired
    private LandlordService landlordService;

    @Autowired
    private StatusService statusService;

    private Landlord testLandlord;

    @BeforeEach
    void setUp() {
        LandlordDTO landlordDTO = TestDataBuilder.buildLandlordDTO();
        testLandlord = landlordService.createLandlord(landlordDTO);
    }

    @Nested
    @DisplayName("房源状态流转测试")
    class HouseStatusFlowTests {

        @Test
        @DisplayName("状态流转: available -> rented (可租赁->已出租)")
        void testStatusFlow_AvailableToRented() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            assertEquals("available", house.getHouseStatus());

            House updated = houseService.updateHouseStatus(house.getHouseId(), "rented");

            assertEquals("rented", updated.getHouseStatus());
        }

        @Test
        @DisplayName("状态流转: available -> offline (可租赁->已下架)")
        void testStatusFlow_AvailableToOffline() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            assertEquals("available", house.getHouseStatus());

            House updated = houseService.updateHouseStatus(house.getHouseId(), "offline");

            assertEquals("offline", updated.getHouseStatus());
        }

        @Test
        @DisplayName("状态流转: rented -> available (已出租->可租赁)")
        void testStatusFlow_RentedToAvailable() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            houseService.updateHouseStatus(house.getHouseId(), "rented");
            assertEquals("rented", houseService.getHouseById(house.getHouseId()).getHouseStatus());

            House updated = houseService.updateHouseStatus(house.getHouseId(), "available");

            assertEquals("available", updated.getHouseStatus());
        }

        @Test
        @DisplayName("状态流转: rented -> offline (已出租->已下架)")
        void testStatusFlow_RentedToOffline() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            houseService.updateHouseStatus(house.getHouseId(), "rented");
            assertEquals("rented", houseService.getHouseById(house.getHouseId()).getHouseStatus());

            House updated = houseService.updateHouseStatus(house.getHouseId(), "offline");

            assertEquals("offline", updated.getHouseStatus());
        }

        @Test
        @DisplayName("状态流转: offline -> available (已下架->可租赁)")
        void testStatusFlow_OfflineToAvailable() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            houseService.updateHouseStatus(house.getHouseId(), "offline");
            assertEquals("offline", houseService.getHouseById(house.getHouseId()).getHouseStatus());

            House updated = houseService.updateHouseStatus(house.getHouseId(), "available");

            assertEquals("available", updated.getHouseStatus());
        }

        @Test
        @DisplayName("完整状态生命周期测试")
        void testCompleteStatusLifecycle() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);
            String houseId = house.getHouseId();

            assertEquals("available", houseService.getHouseById(houseId).getHouseStatus());

            houseService.updateHouseStatus(houseId, "rented");
            assertEquals("rented", houseService.getHouseById(houseId).getHouseStatus());

            houseService.updateHouseStatus(houseId, "available");
            assertEquals("available", houseService.getHouseById(houseId).getHouseStatus());

            houseService.updateHouseStatus(houseId, "offline");
            assertEquals("offline", houseService.getHouseById(houseId).getHouseStatus());

            houseService.updateHouseStatus(houseId, "available");
            assertEquals("available", houseService.getHouseById(houseId).getHouseStatus());
        }

        @Test
        @DisplayName("状态流转 - 统计同步更新")
        void testStatusFlow_StatisticsUpdated() {
            long beforeAvailable = houseService.countAvailableHouses();
            long beforeRented = houseService.countRentedHouses();

            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            long afterCreateAvailable = houseService.countAvailableHouses();
            assertEquals(beforeAvailable + 1, afterCreateAvailable);

            houseService.updateHouseStatus(house.getHouseId(), "rented");

            long afterRentedAvailable = houseService.countAvailableHouses();
            long afterRentedRented = houseService.countRentedHouses();

            assertEquals(beforeAvailable, afterRentedAvailable);
            assertEquals(beforeRented + 1, afterRentedRented);
        }
    }

    @Nested
    @DisplayName("房源类型动态加载测试")
    class HouseTypeDynamicLoadingTests {

        private House apartment;
        private House house;
        private House villa;
        private House studio;

        @BeforeEach
        void setUpHouses() {
            apartment = houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "北京市朝阳区",
                    "apartment",
                    80.0,
                    3000.0
            ));

            house = houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "北京市海淀区",
                    "house",
                    120.0,
                    5000.0
            ));

            villa = houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "北京市西城区",
                    "villa",
                    200.0,
                    10000.0
            ));

            studio = houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "北京市东城区",
                    "studio",
                    40.0,
                    2000.0
            ));
        }

        @Test
        @DisplayName("房源类型加载 - apartment类型")
        void testHouseTypeLoading_Apartment() {
            House found = houseService.getHouseById(apartment.getHouseId());

            assertEquals("apartment", found.getHouseType());
            assertEquals("北京市朝阳区", found.getHouseAddress());
            assertEquals(80.0, found.getHouseArea());
            assertEquals(3000.0, found.getHouseRent());
        }

        @Test
        @DisplayName("房源类型加载 - house类型")
        void testHouseTypeLoading_House() {
            House found = houseService.getHouseById(house.getHouseId());

            assertEquals("house", found.getHouseType());
            assertEquals("北京市海淀区", found.getHouseAddress());
            assertEquals(120.0, found.getHouseArea());
            assertEquals(5000.0, found.getHouseRent());
        }

        @Test
        @DisplayName("房源类型加载 - villa类型")
        void testHouseTypeLoading_Villa() {
            House found = houseService.getHouseById(villa.getHouseId());

            assertEquals("villa", found.getHouseType());
            assertEquals("北京市西城区", found.getHouseAddress());
            assertEquals(200.0, found.getHouseArea());
            assertEquals(10000.0, found.getHouseRent());
        }

        @Test
        @DisplayName("房源类型加载 - studio类型")
        void testHouseTypeLoading_Studio() {
            House found = houseService.getHouseById(studio.getHouseId());

            assertEquals("studio", found.getHouseType());
            assertEquals("北京市东城区", found.getHouseAddress());
            assertEquals(40.0, found.getHouseArea());
            assertEquals(2000.0, found.getHouseRent());
        }

        @Test
        @DisplayName("按类型搜索房源 - apartment")
        void testSearchByType_Apartment() {
            HouseSearchDTO searchDTO = TestDataBuilder.SearchTestScenarios.buildTypeSearch("apartment");
            List<House> results = houseService.searchHouses(searchDTO);

            assertFalse(results.isEmpty());
            assertTrue(results.stream().allMatch(h -> "apartment".equals(h.getHouseType())));
        }

        @Test
        @DisplayName("按类型搜索房源 - house")
        void testSearchByType_House() {
            HouseSearchDTO searchDTO = TestDataBuilder.SearchTestScenarios.buildTypeSearch("house");
            List<House> results = houseService.searchHouses(searchDTO);

            assertFalse(results.isEmpty());
            assertTrue(results.stream().allMatch(h -> "house".equals(h.getHouseType())));
        }

        @Test
        @DisplayName("不同类型房源的租金差异")
        void testRentDifferenceByType() {
            double apartmentRent = apartment.getHouseRent();
            double houseRent = house.getHouseRent();
            double villaRent = villa.getHouseRent();
            double studioRent = studio.getHouseRent();

            assertTrue(villaRent > houseRent);
            assertTrue(houseRent > apartmentRent);
            assertTrue(apartmentRent > studioRent);
        }

        @Test
        @DisplayName("不同类型房源的面积差异")
        void testAreaDifferenceByType() {
            double apartmentArea = apartment.getHouseArea();
            double houseArea = house.getHouseArea();
            double villaArea = villa.getHouseArea();
            double studioArea = studio.getHouseArea();

            assertTrue(villaArea > houseArea);
            assertTrue(houseArea > apartmentArea);
            assertTrue(apartmentArea > studioArea);
        }
    }

    @Nested
    @DisplayName("房源搜索功能测试")
    class HouseSearchTests {

        @BeforeEach
        void setUpMultipleHouses() {
            houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "北京市朝阳区朝阳公园",
                    "apartment",
                    80.0,
                    3000.0
            ));

            houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "北京市海淀区中关村",
                    "apartment",
                    60.0,
                    4000.0
            ));

            houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "北京市西城区金融街",
                    "house",
                    150.0,
                    8000.0
            ));

            houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "北京市东城区王府井",
                    "studio",
                    30.0,
                    2500.0
            ));

            houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "北京市丰台区西站",
                    "apartment",
                    70.0,
                    3500.0
            ));
        }

        @Test
        @DisplayName("按租金范围搜索")
        void testSearchByRentRange() {
            HouseSearchDTO searchDTO = TestDataBuilder.SearchTestScenarios.buildRentRangeSearch(3000.0, 4000.0);
            List<House> results = houseService.searchHouses(searchDTO);

            assertFalse(results.isEmpty());
            assertTrue(results.stream().allMatch(h ->
                    h.getHouseRent() >= 3000.0 && h.getHouseRent() <= 4000.0
            ));
        }

        @Test
        @DisplayName("按面积范围搜索")
        void testSearchByAreaRange() {
            HouseSearchDTO searchDTO = TestDataBuilder.SearchTestScenarios.buildAreaRangeSearch(50.0, 100.0);
            List<House> results = houseService.searchHouses(searchDTO);

            assertFalse(results.isEmpty());
            assertTrue(results.stream().allMatch(h ->
                    h.getHouseArea() >= 50.0 && h.getHouseArea() <= 100.0
            ));
        }

        @Test
        @DisplayName("按关键词搜索")
        void testSearchByKeyword() {
            HouseSearchDTO searchDTO = TestDataBuilder.SearchTestScenarios.buildKeywordSearch("朝阳");
            List<House> results = houseService.searchHouses(searchDTO);

            assertFalse(results.isEmpty());
            assertTrue(results.stream().allMatch(h ->
                    h.getHouseAddress().contains("朝阳")
            ));
        }

        @Test
        @DisplayName("组合搜索 - 租金范围 + 类型")
        void testSearchByCombinedCriteria() {
            HouseSearchDTO searchDTO = TestDataBuilder.SearchTestScenarios.buildCombinedSearch(
                    2000.0, 5000.0, "apartment"
            );
            List<House> results = houseService.searchHouses(searchDTO);

            assertFalse(results.isEmpty());
            assertTrue(results.stream().allMatch(h ->
                    h.getHouseRent() >= 2000.0 &&
                    h.getHouseRent() <= 5000.0 &&
                    "apartment".equals(h.getHouseType())
            ));
        }

        @Test
        @DisplayName("搜索结果按租金排序验证")
        void testSearchResults_ByRentValidation() {
            HouseSearchDTO searchDTO = TestDataBuilder.SearchTestScenarios.buildRentRangeSearch(0.0, 10000.0);
            List<House> results = houseService.searchHouses(searchDTO);

            assertFalse(results.isEmpty());

            double minRent = results.stream().mapToDouble(House::getHouseRent).min().orElse(0);
            double maxRent = results.stream().mapToDouble(House::getHouseRent).max().orElse(0);

            assertTrue(minRent < maxRent);
        }
    }

    @Nested
    @DisplayName("房源CRUD操作测试")
    class HouseCRUDTests {

        @Test
        @DisplayName("创建房源成功")
        void testCreateHouse_Success() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());

            House house = houseService.createHouse(houseDTO);

            assertNotNull(house);
            assertNotNull(house.getHouseId());
            assertTrue(house.getHouseId().startsWith("house_"));
            assertEquals(houseDTO.getHouseAddress(), house.getHouseAddress());
            assertEquals(houseDTO.getHouseType(), house.getHouseType());
            assertEquals(houseDTO.getHouseArea(), house.getHouseArea());
            assertEquals(houseDTO.getHouseRent(), house.getHouseRent());
            assertEquals("available", house.getHouseStatus());
            assertEquals(testLandlord.getLandlordId(), house.getLandlordId());
        }

        @Test
        @DisplayName("创建房源失败 - 房东不存在")
        void testCreateHouse_Fail_LandlordNotExist() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO("non_existent_landlord");

            assertThrows(Exception.class, () -> houseService.createHouse(houseDTO));
        }

        @Test
        @DisplayName("获取房源成功")
        void testGetHouseById_Success() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House created = houseService.createHouse(houseDTO);

            House found = houseService.getHouseById(created.getHouseId());

            assertNotNull(found);
            assertEquals(created.getHouseId(), found.getHouseId());
        }

        @Test
        @DisplayName("获取房源失败 - 不存在")
        void testGetHouseById_Fail_NotExist() {
            assertThrows(HouseRentalException.class,
                    () -> houseService.getHouseById("non_existent_house"));
        }

        @Test
        @DisplayName("更新房源成功")
        void testUpdateHouse_Success() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House created = houseService.createHouse(houseDTO);

            HouseDTO updateDTO = new HouseDTO();
            updateDTO.setHouseRent(3500.0);
            updateDTO.setHouseArea(85.0);

            House updated = houseService.updateHouse(created.getHouseId(), updateDTO);

            assertEquals(3500.0, updated.getHouseRent());
            assertEquals(85.0, updated.getHouseArea());
        }

        @Test
        @DisplayName("删除房源成功")
        void testDeleteHouse_Success() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House created = houseService.createHouse(houseDTO);

            long beforeCount = houseService.countTotalHouses();

            houseService.deleteHouse(created.getHouseId());

            long afterCount = houseService.countTotalHouses();
            assertEquals(beforeCount - 1, afterCount);
        }

        @Test
        @DisplayName("删除房源失败 - 已出租的房源")
        void testDeleteHouse_Fail_RentedHouse() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House created = houseService.createHouse(houseDTO);

            houseService.updateHouseStatus(created.getHouseId(), "rented");

            assertThrows(HouseRentalException.class,
                    () -> houseService.deleteHouse(created.getHouseId()));
        }

        @Test
        @DisplayName("删除房源失败 - 已下架的房源")
        void testDeleteHouse_Fail_OfflineHouse() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House created = houseService.createHouse(houseDTO);

            houseService.updateHouseStatus(created.getHouseId(), "offline");

            assertThrows(HouseRentalException.class,
                    () -> houseService.deleteHouse(created.getHouseId()));
        }
    }

    @Nested
    @DisplayName("房源状态管理测试")
    class HouseStatusManagementTests {

        @Test
        @DisplayName("检查房源可用状态 - 可用")
        void testIsHouseAvailable_True() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            assertTrue(houseService.isHouseAvailable(house.getHouseId()));
        }

        @Test
        @DisplayName("检查房源可用状态 - 已出租")
        void testIsHouseAvailable_False_WhenRented() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            houseService.updateHouseStatus(house.getHouseId(), "rented");

            assertFalse(houseService.isHouseAvailable(house.getHouseId()));
        }

        @Test
        @DisplayName("检查房源可用状态 - 已下架")
        void testIsHouseAvailable_False_WhenOffline() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            houseService.updateHouseStatus(house.getHouseId(), "offline");

            assertFalse(houseService.isHouseAvailable(house.getHouseId()));
        }

        @Test
        @DisplayName("验证房源可用 - 已出租时抛出异常")
        void testValidateHouseAvailable_Fail_WhenRented() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            houseService.updateHouseStatus(house.getHouseId(), "rented");

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> houseService.validateHouseAvailable(house.getHouseId())
            );

            assertTrue(exception.getMessage().contains("已出租"));
        }

        @Test
        @DisplayName("验证房源可用 - 已下架时抛出异常")
        void testValidateHouseAvailable_Fail_WhenOffline() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            houseService.updateHouseStatus(house.getHouseId(), "offline");

            HouseRentalException exception = assertThrows(
                    HouseRentalException.class,
                    () -> houseService.validateHouseAvailable(house.getHouseId())
            );

            assertTrue(exception.getMessage().contains("已下架"));
        }

        @Test
        @DisplayName("验证房源可用 - 可用时不抛出异常")
        void testValidateHouseAvailable_Success_WhenAvailable() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            assertDoesNotThrow(() -> houseService.validateHouseAvailable(house.getHouseId()));
        }

        @Test
        @DisplayName("获取房源状态信息")
        void testGetHouseStatusInfo() {
            HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
            House house = houseService.createHouse(houseDTO);

            Map<String, Object> statusInfo = statusService.getHouseStatusInfo(house.getHouseId());

            assertNotNull(statusInfo);
            assertEquals(house.getHouseId(), statusInfo.get("houseId"));
            assertEquals("available", statusInfo.get("houseStatus"));
            assertEquals(house.getHouseAddress(), statusInfo.get("houseAddress"));
            assertEquals(house.getHouseRent(), statusInfo.get("houseRent"));
        }

        @Test
        @DisplayName("获取系统状态摘要")
        void testGetSystemStatusSummary() {
            Map<String, Object> summary = statusService.getSystemStatusSummary();

            assertNotNull(summary);
            assertTrue(summary.containsKey("totalHouses"));
            assertTrue(summary.containsKey("availableHouses"));
            assertTrue(summary.containsKey("rentedHouses"));
            assertTrue(summary.containsKey("activeContracts"));
            assertTrue(summary.containsKey("utilizationRate"));
        }

        @Test
        @DisplayName("验证有效房源状态")
        void testIsValidHouseStatus() {
            assertTrue(statusService.isValidHouseStatus("available"));
            assertTrue(statusService.isValidHouseStatus("rented"));
            assertTrue(statusService.isValidHouseStatus("offline"));
            assertTrue(statusService.isValidHouseStatus("maintenance"));
            assertFalse(statusService.isValidHouseStatus("invalid_status"));
        }
    }

    @Nested
    @DisplayName("房源统计测试")
    class HouseStatisticsTests {

        @Test
        @DisplayName("房源总数统计")
        void testCountTotalHouses() {
            long before = houseService.countTotalHouses();

            houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "房源1",
                    "apartment",
                    80.0,
                    3000.0
            ));
            houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "房源2",
                    "house",
                    120.0,
                    5000.0
            ));

            long after = houseService.countTotalHouses();

            assertEquals(before + 2, after);
        }

        @Test
        @DisplayName("可用房源统计")
        void testCountAvailableHouses() {
            House house1 = houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "房源1",
                    "apartment",
                    80.0,
                    3000.0
            ));
            House house2 = houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "房源2",
                    "house",
                    120.0,
                    5000.0
            ));

            long beforeRented = houseService.countAvailableHouses();

            houseService.updateHouseStatus(house1.getHouseId(), "rented");

            long afterRented = houseService.countAvailableHouses();

            assertEquals(beforeRented - 1, afterRented);
        }

        @Test
        @DisplayName("已租房源统计")
        void testCountRentedHouses() {
            House house = houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "房源1",
                    "apartment",
                    80.0,
                    3000.0
            ));

            long before = houseService.countRentedHouses();

            houseService.updateHouseStatus(house.getHouseId(), "rented");

            long after = houseService.countRentedHouses();

            assertEquals(before + 1, after);
        }

        @Test
        @DisplayName("按房东统计房源")
        void testCountByLandlord() {
            HouseDTO houseDTO1 = TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "房源1",
                    "apartment",
                    80.0,
                    3000.0
            );
            HouseDTO houseDTO2 = TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "房源2",
                    "house",
                    120.0,
                    5000.0
            );

            houseService.createHouse(houseDTO1);
            houseService.createHouse(houseDTO2);

            List<House> landlordHouses = houseService.getHousesByLandlord(testLandlord.getLandlordId());

            assertTrue(landlordHouses.size() >= 2);
            assertTrue(landlordHouses.stream().allMatch(h ->
                    testLandlord.getLandlordId().equals(h.getLandlordId())
            ));
        }

        @Test
        @DisplayName("增加房源申请计数")
        void testIncrementApplicationCount() {
            House house = houseService.createHouse(TestDataBuilder.buildHouseDTO(
                    testLandlord.getLandlordId(),
                    "房源1",
                    "apartment",
                    80.0,
                    3000.0
            ));

            int before = house.getApplicationCount();

            houseService.incrementApplicationCount(house.getHouseId());

            House updated = houseService.getHouseById(house.getHouseId());
            assertEquals(before + 1, updated.getApplicationCount());
        }
    }
}
