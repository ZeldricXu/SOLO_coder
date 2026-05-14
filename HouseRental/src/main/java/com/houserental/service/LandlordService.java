package com.houserental.service;

import com.houserental.dto.LandlordDTO;
import com.houserental.entity.Landlord;
import com.houserental.exception.HouseRentalException;
import com.houserental.repository.LandlordRepository;
import com.houserental.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class LandlordService {

    @Autowired
    private LandlordRepository landlordRepository;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private StatisticsService statisticsService;

    @Transactional
    public Landlord createLandlord(LandlordDTO dto) {
        Optional<Landlord> existing = landlordRepository.findByLandlordPhone(dto.getLandlordPhone());
        if (existing.isPresent()) {
            return existing.get();
        }

        Landlord landlord = new Landlord();
        landlord.setLandlordId(IdGenerator.generateLandlordId());
        landlord.setLandlordName(dto.getLandlordName());
        landlord.setLandlordPhone(dto.getLandlordPhone());
        landlord.setLandlordStatus(dto.getLandlordStatus() != null ? dto.getLandlordStatus() : "active");
        landlord.setHouseCount(0);
        landlord.setRentedCount(0);
        landlord.setTotalIncome(0.0);

        Landlord saved = landlordRepository.save(landlord);
        historyService.recordLandlordHistory(saved.getLandlordId(), "CREATE",
                "房东信息创建成功：" + saved.getLandlordName());
        statisticsService.incrementLandlordCount();
        return saved;
    }

    @Transactional
    public Landlord getLandlordById(String landlordId) {
        return landlordRepository.findByLandlordId(landlordId)
                .orElseThrow(() -> new HouseRentalException(404, "房东不存在: " + landlordId));
    }

    @Transactional
    public Landlord updateLandlord(String landlordId, LandlordDTO dto) {
        Landlord landlord = getLandlordById(landlordId);
        landlord.setLandlordName(dto.getLandlordName());
        if (dto.getLandlordPhone() != null && !dto.getLandlordPhone().equals(landlord.getLandlordPhone())) {
            Optional<Landlord> existing = landlordRepository.findByLandlordPhone(dto.getLandlordPhone());
            if (existing.isPresent() && !existing.get().getLandlordId().equals(landlordId)) {
                throw new HouseRentalException(400, "该联系方式已被其他房东使用");
            }
            landlord.setLandlordPhone(dto.getLandlordPhone());
        }
        if (dto.getLandlordStatus() != null) {
            landlord.setLandlordStatus(dto.getLandlordStatus());
        }
        Landlord saved = landlordRepository.save(landlord);
        historyService.recordLandlordHistory(saved.getLandlordId(), "UPDATE",
                "房东信息更新成功");
        return saved;
    }

    @Transactional
    public void incrementHouseCount(String landlordId) {
        Landlord landlord = getLandlordById(landlordId);
        landlord.setHouseCount(landlord.getHouseCount() + 1);
        landlordRepository.save(landlord);
        historyService.recordLandlordHistory(landlordId, "HOUSE_ADD",
                "添加房源，当前房源数：" + (landlord.getHouseCount()));
    }

    @Transactional
    public void decrementHouseCount(String landlordId) {
        Landlord landlord = getLandlordById(landlordId);
        if (landlord.getHouseCount() > 0) {
            landlord.setHouseCount(landlord.getHouseCount() - 1);
            landlordRepository.save(landlord);
            historyService.recordLandlordHistory(landlordId, "HOUSE_REMOVE",
                    "移除房源，当前房源数：" + (landlord.getHouseCount()));
        }
    }

    @Transactional
    public void incrementRentedCount(String landlordId) {
        Landlord landlord = getLandlordById(landlordId);
        landlord.setRentedCount(landlord.getRentedCount() + 1);
        landlordRepository.save(landlord);
        historyService.recordLandlordHistory(landlordId, "RENT_SUCCESS",
                "房源出租成功，当前已出租数：" + (landlord.getRentedCount()));
    }

    @Transactional
    public void decrementRentedCount(String landlordId) {
        Landlord landlord = getLandlordById(landlordId);
        if (landlord.getRentedCount() > 0) {
            landlord.setRentedCount(landlord.getRentedCount() - 1);
            landlordRepository.save(landlord);
            historyService.recordLandlordHistory(landlordId, "RENT_END",
                    "房源租约结束，当前已出租数：" + (landlord.getRentedCount()));
        }
    }

    @Transactional
    public void addIncome(String landlordId, double amount) {
        Landlord landlord = getLandlordById(landlordId);
        landlord.setTotalIncome(landlord.getTotalIncome() + amount);
        landlordRepository.save(landlord);
        historyService.recordLandlordHistory(landlordId, "INCOME_ADD",
                "收到租金收入：" + amount + "，累计收入：" + landlord.getTotalIncome());
    }

    public List<Landlord> getAllLandlords() {
        return landlordRepository.findAll();
    }

    public List<Landlord> getActiveLandlords() {
        return landlordRepository.findByLandlordStatus("active");
    }

    public long countTotalLandlords() {
        return landlordRepository.countTotalLandlords();
    }

    public long countActiveLandlords() {
        return landlordRepository.countByStatus("active");
    }

    @Transactional
    public void notifyLandlord(String landlordId, String message) {
        historyService.recordLandlordHistory(landlordId, "NOTIFY", message);
    }
}
