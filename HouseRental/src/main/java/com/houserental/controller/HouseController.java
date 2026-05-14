package com.houserental.controller;

import com.houserental.dto.ApiResponse;
import com.houserental.dto.HouseDTO;
import com.houserental.dto.HouseSearchDTO;
import com.houserental.entity.House;
import com.houserental.service.HouseService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/houses")
public class HouseController {

    @Autowired
    private HouseService houseService;

    @PostMapping("/create")
    public ApiResponse<House> createHouse(@Valid @RequestBody HouseDTO dto) {
        House house = houseService.createHouse(dto);
        return ApiResponse.success(house);
    }

    @GetMapping("/{houseId}")
    public ApiResponse<House> getHouseById(@PathVariable String houseId) {
        House house = houseService.getHouseById(houseId);
        return ApiResponse.success(house);
    }

    @PutMapping("/{houseId}")
    public ApiResponse<House> updateHouse(@PathVariable String houseId, @RequestBody HouseDTO dto) {
        House house = houseService.updateHouse(houseId, dto);
        return ApiResponse.success(house);
    }

    @DeleteMapping("/{houseId}")
    public ApiResponse<Void> deleteHouse(@PathVariable String houseId) {
        houseService.deleteHouse(houseId);
        return ApiResponse.success(null);
    }

    @GetMapping("/list")
    public ApiResponse<List<House>> getAllHouses() {
        List<House> houses = houseService.getAllHouses();
        return ApiResponse.success(houses);
    }

    @GetMapping("/available")
    public ApiResponse<List<House>> getAvailableHouses() {
        List<House> houses = houseService.getAvailableHouses();
        return ApiResponse.success(houses);
    }

    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> searchHouses(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String houseType,
            @RequestParam(required = false) Double minRent,
            @RequestParam(required = false) Double maxRent,
            @RequestParam(required = false) Double minArea,
            @RequestParam(required = false) Double maxArea,
            @RequestParam(required = false) String landlordId) {

        HouseSearchDTO searchDTO = new HouseSearchDTO();
        searchDTO.setKeyword(keyword);
        searchDTO.setHouseType(houseType);
        searchDTO.setMinRent(minRent);
        searchDTO.setMaxRent(maxRent);
        searchDTO.setMinArea(minArea);
        searchDTO.setMaxArea(maxArea);
        searchDTO.setLandlordId(landlordId);

        List<House> houses = houseService.searchHouses(searchDTO);

        List<Map<String, Object>> houseList = houses.stream().map(house -> {
            Map<String, Object> item = new HashMap<>();
            item.put("house_id", house.getHouseId());
            item.put("address", house.getHouseAddress());
            item.put("house_type", house.getHouseType());
            item.put("area", house.getHouseArea());
            item.put("rent", house.getHouseRent());
            item.put("status", house.getHouseStatus());
            item.put("features", house.getHouseFeatures());
            item.put("landlord_id", house.getLandlordId());
            return item;
        }).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("houses", houseList);
        result.put("total", houses.size());

        return ApiResponse.success(result);
    }

    @GetMapping("/landlord/{landlordId}")
    public ApiResponse<List<House>> getHousesByLandlord(@PathVariable String landlordId) {
        List<House> houses = houseService.getHousesByLandlord(landlordId);
        return ApiResponse.success(houses);
    }

    @GetMapping("/landlord/{landlordId}/available")
    public ApiResponse<List<House>> getAvailableHousesByLandlord(@PathVariable String landlordId) {
        List<House> houses = houseService.getAvailableHousesByLandlord(landlordId);
        return ApiResponse.success(houses);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getHouseStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", houseService.countTotalHouses());
        stats.put("available", houseService.countAvailableHouses());
        stats.put("rented", houseService.countRentedHouses());
        return ApiResponse.success(stats);
    }
}
