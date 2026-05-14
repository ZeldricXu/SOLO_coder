package com.houserental.service;

import com.houserental.config.HouseTypeConfig;
import com.houserental.config.HouseTypeConfig.HouseType;
import com.houserental.dto.HouseDTO;
import com.houserental.dto.HouseSearchDTO;
import com.houserental.entity.House;
import com.houserental.exception.HouseRentalException;
import com.houserental.repository.HouseRepository;
import com.houserental.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

@Service
public class HouseService {

    @Autowired
    private HouseRepository houseRepository;

    @Autowired
    private LandlordService landlordService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private HouseTypeConfig houseTypeConfig;

    @Transactional
    public House createHouse(HouseDTO dto) {
        landlordService.getLandlordById(dto.getLandlordId());

        String houseType = validateAndNormalizeHouseType(dto.getHouseType());

        House house = new House();
        house.setHouseId(IdGenerator.generateHouseId());
        house.setHouseAddress(dto.getHouseAddress());
        house.setHouseType(houseType);
        house.setHouseArea(dto.getHouseArea());
        house.setHouseRent(dto.getHouseRent());
        house.setHouseStatus("available");
        house.setHouseFeatures(dto.getHouseFeatures());
        house.setLandlordId(dto.getLandlordId());
        house.setApplicationCount(0);

        House saved = houseRepository.save(house);
        landlordService.incrementHouseCount(dto.getLandlordId());
        historyService.recordHouseHistory(saved.getHouseId(), "CREATE",
                "房源创建成功：" + saved.getHouseAddress(), dto.getLandlordId());
        statisticsService.incrementHouseCount();
        return saved;
    }

    @Transactional
    public House getHouseById(String houseId) {
        return houseRepository.findByHouseId(houseId)
                .orElseThrow(() -> new HouseRentalException(404, "房源不存在: " + houseId));
    }

    @Transactional
    public House updateHouse(String houseId, HouseDTO dto) {
        House house = getHouseById(houseId);
        if (dto.getHouseAddress() != null) {
            house.setHouseAddress(dto.getHouseAddress());
        }
        if (dto.getHouseType() != null) {
            house.setHouseType(validateAndNormalizeHouseType(dto.getHouseType()));
        }
        if (dto.getHouseArea() != null) {
            house.setHouseArea(dto.getHouseArea());
        }
        if (dto.getHouseRent() != null) {
            house.setHouseRent(dto.getHouseRent());
        }
        if (dto.getHouseFeatures() != null) {
            house.setHouseFeatures(dto.getHouseFeatures());
        }

        House saved = houseRepository.save(house);
        historyService.recordHouseHistory(saved.getHouseId(), "UPDATE",
                "房源信息更新成功", saved.getLandlordId());
        return saved;
    }

    @Transactional
    public void deleteHouse(String houseId) {
        House house = getHouseById(houseId);
        if (!"available".equals(house.getHouseStatus())) {
            throw new HouseRentalException(400, "房源已出租或下架，无法删除");
        }
        houseRepository.delete(house);
        landlordService.decrementHouseCount(house.getLandlordId());
        historyService.recordHouseHistory(houseId, "DELETE",
                "房源已删除", house.getLandlordId());
        statisticsService.decrementHouseCount();
    }

    @Transactional
    public House updateHouseStatus(String houseId, String status) {
        House house = getHouseById(houseId);
        String oldStatus = house.getHouseStatus();

        if ("rented".equals(status) && "available".equals(oldStatus)) {
            statisticsService.decrementAvailableHouseCount();
        } else if ("available".equals(status) && "rented".equals(oldStatus)) {
            statisticsService.incrementAvailableHouseCount();
        }

        house.setHouseStatus(status);
        House saved = houseRepository.save(house);
        historyService.recordHouseHistory(saved.getHouseId(), "STATUS_CHANGE",
                "房源状态变更：" + oldStatus + " -> " + status, saved.getLandlordId());
        return saved;
    }

    @Transactional
    public void incrementApplicationCount(String houseId) {
        House house = getHouseById(houseId);
        house.setApplicationCount(house.getApplicationCount() + 1);
        houseRepository.save(house);
        historyService.recordHouseHistory(houseId, "APPLICATION_ADD",
                "收到新的租赁申请，申请数：" + (house.getApplicationCount()), house.getLandlordId());
    }

    public List<House> getAllHouses() {
        return houseRepository.findAll();
    }

    public List<House> getAvailableHouses() {
        return houseRepository.findByHouseStatus("available");
    }

    public List<House> getRentedHouses() {
        return houseRepository.findByHouseStatus("rented");
    }

    public List<House> getHousesByLandlord(String landlordId) {
        return houseRepository.findByLandlordId(landlordId);
    }

    public List<House> getAvailableHousesByLandlord(String landlordId) {
        return houseRepository.findByLandlordIdAndHouseStatus(landlordId, "available");
    }

    public List<House> searchHouses(HouseSearchDTO searchDTO) {
        if (searchDTO.getHouseType() != null && !searchDTO.getHouseType().isEmpty()) {
            if (!houseTypeConfig.isValidType(searchDTO.getHouseType())) {
                return new ArrayList<>();
            }
        }

        Specification<House> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("houseStatus"), "available"));

            if (searchDTO.getKeyword() != null && !searchDTO.getKeyword().isEmpty()) {
                predicates.add(cb.like(root.get("houseAddress"), "%" + searchDTO.getKeyword() + "%"));
            }

            if (searchDTO.getHouseType() != null && !searchDTO.getHouseType().isEmpty()) {
                predicates.add(cb.equal(root.get("houseType"), searchDTO.getHouseType()));
            }

            if (searchDTO.getMinRent() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("houseRent"), searchDTO.getMinRent()));
            }

            if (searchDTO.getMaxRent() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("houseRent"), searchDTO.getMaxRent()));
            }

            if (searchDTO.getMinArea() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("houseArea"), searchDTO.getMinArea()));
            }

            if (searchDTO.getMaxArea() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("houseArea"), searchDTO.getMaxArea()));
            }

            if (searchDTO.getLandlordId() != null && !searchDTO.getLandlordId().isEmpty()) {
                predicates.add(cb.equal(root.get("landlordId"), searchDTO.getLandlordId()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return houseRepository.findAll(spec);
    }

    public List<House> searchByRentRange(double minRent, double maxRent) {
        return houseRepository.findByRentRange(minRent, maxRent);
    }

    public List<House> searchByKeyword(String keyword) {
        return houseRepository.searchByKeyword(keyword);
    }

    public long countTotalHouses() {
        return houseRepository.countTotalHouses();
    }

    public long countAvailableHouses() {
        return houseRepository.countByStatus("available");
    }

    public long countRentedHouses() {
        return houseRepository.countByStatus("rented");
    }

    public boolean isHouseAvailable(String houseId) {
        House house = getHouseById(houseId);
        return "available".equals(house.getHouseStatus());
    }

    public void validateHouseAvailable(String houseId) {
        House house = getHouseById(houseId);
        if ("rented".equals(house.getHouseStatus())) {
            throw new HouseRentalException(400, "房源已出租");
        }
        if ("offline".equals(house.getHouseStatus())) {
            throw new HouseRentalException(400, "房源已下架");
        }
        if (!"available".equals(house.getHouseStatus())) {
            throw new HouseRentalException(400, "房源状态异常，无法申请");
        }
    }

    private String validateAndNormalizeHouseType(String typeCode) {
        if (typeCode == null || typeCode.isEmpty()) {
            return houseTypeConfig.getDefaultType();
        }
        
        if (houseTypeConfig.isValidType(typeCode)) {
            return typeCode;
        }
        
        throw new HouseRentalException(400, "无效的房源类型: " + typeCode + 
                "，可用类型: " + houseTypeConfig.getEnabledTypeCodes());
    }

    public List<HouseType> getEnabledHouseTypes() {
        return houseTypeConfig.getEnabledTypes();
    }

    public List<String> getEnabledHouseTypeCodes() {
        return houseTypeConfig.getEnabledTypeCodes();
    }

    public HouseType getHouseTypeInfo(String typeCode) {
        return houseTypeConfig.getType(typeCode);
    }

    public List<HouseType> getHouseTypesByCategory(String category) {
        return houseTypeConfig.getTypesByCategory(category);
    }
}
