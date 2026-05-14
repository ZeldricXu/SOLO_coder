package com.homeservice.service;

import com.homeservice.dto.StatisticsResponse;
import com.homeservice.entity.ServiceStat;
import com.homeservice.enums.BookingStatus;
import com.homeservice.enums.SettlementStatus;
import com.homeservice.repository.BookingRepository;
import com.homeservice.repository.CustomerRepository;
import com.homeservice.repository.ReviewRepository;
import com.homeservice.repository.ServiceStatRepository;
import com.homeservice.repository.SettlementRepository;
import com.homeservice.repository.StaffRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AnalyticsService {

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private ServiceStatRepository serviceStatRepository;

    private final AtomicLong statCounter = new AtomicLong(0);

    public StatisticsResponse getOverallStatistics() {
        StatisticsResponse stats = new StatisticsResponse();
        stats.setTotalStaff(staffRepository.countTotalStaff());
        stats.setTotalCustomers(customerRepository.countTotalCustomers());
        stats.setTotalBookings(bookingRepository.count());
        stats.setCompletedBookings(bookingRepository.countByStatus(BookingStatus.COMPLETED));
        stats.setTotalReviews(reviewRepository.countTotalReviews());
        stats.setAverageRating(staffRepository.getAverageRating());
        stats.setTotalRevenue(settlementRepository.sumServiceAmountByStatus(SettlementStatus.PAID));
        stats.setMonth(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")));
        return stats;
    }

    public void incrementBookingCount() {
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        ServiceStat stat = getOrCreateMonthlyStat(currentMonth);
        stat.setBookingCount(stat.getBookingCount() + 1);
        serviceStatRepository.save(stat);
    }

    public void incrementReviewCount() {
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        ServiceStat stat = getOrCreateMonthlyStat(currentMonth);
        stat.setReviewCount(stat.getReviewCount() + 1);
        serviceStatRepository.save(stat);
    }

    public void addToTotalRevenue(Double amount) {
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        ServiceStat stat = getOrCreateMonthlyStat(currentMonth);
        stat.setTotalAmount(stat.getTotalAmount() + amount);
        serviceStatRepository.save(stat);
    }

    private ServiceStat getOrCreateMonthlyStat(String month) {
        return serviceStatRepository.findByStatMonth(month).orElseGet(() -> {
            String statId = "stat_" + String.format("%03d", statCounter.incrementAndGet());
            ServiceStat newStat = new ServiceStat(statId, month);
            newStat.setStaffCount(staffRepository.countTotalStaff().intValue());
            return serviceStatRepository.save(newStat);
        });
    }

    public ServiceStat getMonthlyStat(String month) {
        return serviceStatRepository.findByStatMonth(month).orElse(null);
    }
}
