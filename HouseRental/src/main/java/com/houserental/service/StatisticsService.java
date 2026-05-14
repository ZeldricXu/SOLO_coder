package com.houserental.service;

import com.houserental.entity.Statistics;
import com.houserental.repository.ApplicationRepository;
import com.houserental.repository.ContractRepository;
import com.houserental.repository.HouseRepository;
import com.houserental.repository.LandlordRepository;
import com.houserental.repository.PaymentRepository;
import com.houserental.repository.StatisticsRepository;
import com.houserental.repository.TenantRepository;
import com.houserental.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class StatisticsService {

    @Autowired
    private StatisticsRepository statisticsRepository;

    @Autowired
    private HouseRepository houseRepository;

    @Autowired
    private LandlordRepository landlordRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    private String getCurrentMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    @Transactional
    public Statistics getOrCreateCurrentMonthStatistics() {
        String currentMonth = getCurrentMonth();
        Optional<Statistics> existing = statisticsRepository.findByStatMonth(currentMonth);
        if (existing.isPresent()) {
            return existing.get();
        }

        Statistics stat = new Statistics();
        stat.setStatId(IdGenerator.generateStatId());
        stat.setStatMonth(currentMonth);
        stat.setHouseCount((int) houseRepository.countTotalHouses());
        stat.setAvailableHouseCount((int) houseRepository.countByStatus("available"));
        stat.setRentedHouseCount((int) houseRepository.countByStatus("rented"));
        stat.setLandlordCount((int) landlordRepository.countTotalLandlords());
        stat.setTenantCount((int) tenantRepository.countTotalTenants());
        return statisticsRepository.save(stat);
    }

    @Transactional
    public void incrementHouseCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setHouseCount(stat.getHouseCount() + 1);
        stat.setAvailableHouseCount(stat.getAvailableHouseCount() + 1);
        statisticsRepository.save(stat);
    }

    @Transactional
    public void decrementHouseCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        if (stat.getHouseCount() > 0) {
            stat.setHouseCount(stat.getHouseCount() - 1);
        }
        statisticsRepository.save(stat);
    }

    @Transactional
    public void incrementAvailableHouseCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setAvailableHouseCount(stat.getAvailableHouseCount() + 1);
        if (stat.getRentedHouseCount() > 0) {
            stat.setRentedHouseCount(stat.getRentedHouseCount() - 1);
        }
        statisticsRepository.save(stat);
    }

    @Transactional
    public void decrementAvailableHouseCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        if (stat.getAvailableHouseCount() > 0) {
            stat.setAvailableHouseCount(stat.getAvailableHouseCount() - 1);
        }
        stat.setRentedHouseCount(stat.getRentedHouseCount() + 1);
        statisticsRepository.save(stat);
    }

    @Transactional
    public void incrementApplicationCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setApplicationCount(stat.getApplicationCount() + 1);
        statisticsRepository.save(stat);
    }

    @Transactional
    public void incrementApprovedApplicationCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setApprovedApplicationCount(stat.getApprovedApplicationCount() + 1);
        statisticsRepository.save(stat);
    }

    @Transactional
    public void incrementRejectedApplicationCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setRejectedApplicationCount(stat.getRejectedApplicationCount() + 1);
        statisticsRepository.save(stat);
    }

    @Transactional
    public void incrementContractCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setContractCount(stat.getContractCount() + 1);
        statisticsRepository.save(stat);
    }

    @Transactional
    public void incrementRenewalCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setRenewalCount(stat.getRenewalCount() + 1);
        statisticsRepository.save(stat);
    }

    @Transactional
    public void addRentAmount(double amount) {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setRentAmount(stat.getRentAmount() + amount);
        statisticsRepository.save(stat);
    }

    @Transactional
    public void incrementLandlordCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setLandlordCount(stat.getLandlordCount() + 1);
        statisticsRepository.save(stat);
    }

    @Transactional
    public void incrementTenantCount() {
        Statistics stat = getOrCreateCurrentMonthStatistics();
        stat.setTenantCount(stat.getTenantCount() + 1);
        statisticsRepository.save(stat);
    }

    public Statistics getStatisticsByMonth(String month) {
        return statisticsRepository.findByStatMonth(month)
                .orElse(null);
    }

    public List<Statistics> getStatisticsByMonthRange(String startMonth, String endMonth) {
        return statisticsRepository.findByMonthRange(startMonth, endMonth);
    }

    public List<Statistics> getRecentStatistics(int limit) {
        return statisticsRepository.findRecentStatistics(limit);
    }

    @Transactional
    public Statistics refreshCurrentStatistics() {
        String currentMonth = getCurrentMonth();
        Statistics stat = getOrCreateCurrentMonthStatistics();

        LocalDate now = LocalDate.now();
        LocalDateTime monthStart = now.withDayOfMonth(1).atStartOfDay();
        LocalDateTime monthEnd = now.plusMonths(1).withDayOfMonth(1).atStartOfDay();

        stat.setHouseCount((int) houseRepository.countTotalHouses());
        stat.setAvailableHouseCount((int) houseRepository.countByStatus("available"));
        stat.setRentedHouseCount((int) houseRepository.countByStatus("rented"));
        stat.setLandlordCount((int) landlordRepository.countTotalLandlords());
        stat.setTenantCount((int) tenantRepository.countTotalTenants());
        stat.setApplicationCount((int) applicationRepository.countByTimeRange(monthStart, monthEnd));
        stat.setApprovedApplicationCount((int) applicationRepository.countByStatusAndTimeRange("approved", monthStart, monthEnd));
        stat.setRejectedApplicationCount((int) applicationRepository.countByStatusAndTimeRange("rejected", monthStart, monthEnd));
        stat.setContractCount((int) contractRepository.countTotalContracts());
        stat.setRenewalCount((int) contractRepository.countRenewedContracts());

        Double paidAmount = paymentRepository.sumPaidAmountByTimeRange(monthStart, monthEnd);
        stat.setRentAmount(paidAmount != null ? paidAmount : 0.0);

        return statisticsRepository.save(stat);
    }

    @Transactional
    public Statistics getComprehensiveStatistics() {
        Statistics stat = new Statistics();
        stat.setStatId(IdGenerator.generateStatId());
        stat.setStatMonth(getCurrentMonth());
        stat.setHouseCount((int) houseRepository.countTotalHouses());
        stat.setAvailableHouseCount((int) houseRepository.countByStatus("available"));
        stat.setRentedHouseCount((int) houseRepository.countByStatus("rented"));
        stat.setLandlordCount((int) landlordRepository.countTotalLandlords());
        stat.setTenantCount((int) tenantRepository.countTotalTenants());
        stat.setApplicationCount((int) applicationRepository.countTotalApplications());
        stat.setApprovedApplicationCount((int) applicationRepository.countByStatus("approved"));
        stat.setRejectedApplicationCount((int) applicationRepository.countByStatus("rejected"));
        stat.setContractCount((int) contractRepository.countTotalContracts());
        stat.setRenewalCount((int) contractRepository.countRenewedContracts());

        Double totalPaid = paymentRepository.sumTotalPaidAmount();
        stat.setRentAmount(totalPaid != null ? totalPaid : 0.0);

        return stat;
    }
}
