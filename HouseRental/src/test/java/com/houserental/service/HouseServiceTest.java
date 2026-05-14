package com.houserental.service;

import com.houserental.HouseRentalApplication;
import com.houserental.builder.TestDataBuilder;
import com.houserental.dto.HouseDTO;
import com.houserental.dto.LandlordDTO;
import com.houserental.entity.House;
import com.houserental.entity.Landlord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = HouseRentalApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@Transactional
class HouseServiceTest {

    @Autowired
    private HouseService houseService;

    @Autowired
    private LandlordService landlordService;

    private Landlord testLandlord;

    @BeforeEach
    void setUp() {
        LandlordDTO landlordDTO = TestDataBuilder.buildLandlordDTO();
        testLandlord = landlordService.createLandlord(landlordDTO);
    }

    @Test
    @DisplayName("创建房源成功")
    void testCreateHouse_Success() {
        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());

        House result = houseService.createHouse(houseDTO);

        assertNotNull(result);
        assertNotNull(result.getHouseId());
        assertTrue(result.getHouseId().startsWith("house_"));
        assertEquals(houseDTO.getHouseAddress(), result.getHouseAddress());
        assertEquals(houseDTO.getHouseType(), result.getHouseType());
        assertEquals(houseDTO.getHouseArea(), result.getHouseArea());
        assertEquals(houseDTO.getHouseRent(), result.getHouseRent());
        assertEquals("available", result.getHouseStatus());
        assertEquals(testLandlord.getLandlordId(), result.getLandlordId());
    }

    @Test
    @DisplayName("获取房源信息成功")
    void testGetHouseById_Success() {
        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        House created = houseService.createHouse(houseDTO);

        House result = houseService.getHouseById(created.getHouseId());

        assertNotNull(result);
        assertEquals(created.getHouseId(), result.getHouseId());
    }

    @Test
    @DisplayName("获取可用房源列表")
    void testGetAvailableHouses() {
        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        houseService.createHouse(houseDTO);

        List<House> available = houseService.getAvailableHouses();

        assertFalse(available.isEmpty());
        assertTrue(available.stream().allMatch(h -> "available".equals(h.getHouseStatus())));
    }

    @Test
    @DisplayName("检查房源可用状态")
    void testIsHouseAvailable() {
        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        House created = houseService.createHouse(houseDTO);

        boolean available = houseService.isHouseAvailable(created.getHouseId());

        assertTrue(available);
    }

    @Test
    @DisplayName("更新房源状态成功")
    void testUpdateHouseStatus_Success() {
        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        House created = houseService.createHouse(houseDTO);

        House updated = houseService.updateHouseStatus(created.getHouseId(), "rented");

        assertEquals("rented", updated.getHouseStatus());
    }

    @Test
    @DisplayName("搜索房源按租金范围")
    void testSearchByRentRange() {
        HouseDTO houseDTO1 = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        houseDTO1.setHouseRent(2500.0);
        houseService.createHouse(houseDTO1);

        HouseDTO houseDTO2 = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        houseDTO2.setHouseRent(3500.0);
        houseService.createHouse(houseDTO2);

        List<House> result = houseService.searchByRentRange(2000.0, 3000.0);

        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(h -> h.getHouseRent() >= 2000.0 && h.getHouseRent() <= 3000.0));
    }

    @Test
    @DisplayName("验证房源可用状态 - 已出租")
    void testValidateHouseAvailable_Rented() {
        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        House created = houseService.createHouse(houseDTO);
        houseService.updateHouseStatus(created.getHouseId(), "rented");

        assertThrows(Exception.class, () -> {
            houseService.validateHouseAvailable(created.getHouseId());
        });
    }

    @Test
    @DisplayName("统计房源数量")
    void testCountHouses() {
        HouseDTO houseDTO = TestDataBuilder.buildHouseDTO(testLandlord.getLandlordId());
        houseService.createHouse(houseDTO);

        long total = houseService.countTotalHouses();
        long available = houseService.countAvailableHouses();
        long rented = houseService.countRentedHouses();

        assertTrue(total > 0);
        assertTrue(available > 0);
        assertEquals(0, rented);
    }
}
