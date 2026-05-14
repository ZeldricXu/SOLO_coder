package com.hotelbooking.service;

import com.hotelbooking.model.Hotel;
import com.hotelbooking.model.HotelStat;
import com.hotelbooking.repository.*;
import com.hotelbooking.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class AnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final HotelStatRepository hotelStatRepository;
    private final BookingRepository bookingRepository;
    private final CheckInRepository checkInRepository;
    private final SettlementRepository settlementRepository;
    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;

    public AnalysisService(HotelStatRepository hotelStatRepository, BookingRepository bookingRepository,
                           CheckInRepository checkInRepository, SettlementRepository settlementRepository,
                           HotelRepository hotelRepository, RoomRepository roomRepository) {
        this.hotelStatRepository = hotelStatRepository;
        this.bookingRepository = bookingRepository;
        this.checkInRepository = checkInRepository;
        this.settlementRepository = settlementRepository;
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
    }

    private String getCurrentMonth() {
        return LocalDate.now().format(MONTH_FORMATTER);
    }

    @Transactional
    public void incrementBookingCount(String hotelId) {
        String month = getCurrentMonth();
        HotelStat stat = getOrCreateHotelStat(hotelId, month);
        stat.setBookingCount(stat.getBookingCount() + 1);
        hotelStatRepository.save(stat);
        logger.info("预订统计更新: 酒店={}, 月份={}, 预订数={}", hotelId, month, stat.getBookingCount());
    }

    @Transactional
    public void incrementCheckInCount(String hotelId) {
        String month = getCurrentMonth();
        HotelStat stat = getOrCreateHotelStat(hotelId, month);
        stat.setCheckinCount(stat.getCheckinCount() + 1);
        updateOccupancyRate(stat);
        hotelStatRepository.save(stat);
        logger.info("入住统计更新: 酒店={}, 月份={}, 入住数={}", hotelId, month, stat.getCheckinCount());
    }

    @Transactional
    public void addRevenue(String hotelId, Double amount) {
        String month = getCurrentMonth();
        HotelStat stat = getOrCreateHotelStat(hotelId, month);
        stat.setTotalAmount(stat.getTotalAmount() + amount);
        hotelStatRepository.save(stat);
        logger.info("收入统计更新: 酒店={}, 月份={}, 收入={}", hotelId, month, stat.getTotalAmount());
    }

    private HotelStat getOrCreateHotelStat(String hotelId, String month) {
        Optional<HotelStat> existing = hotelStatRepository.findByHotelIdAndStatMonth(hotelId, month);
        if (existing.isPresent()) {
            return existing.get();
        }
        HotelStat stat = new HotelStat();
        stat.setStatId(IdGenerator.generateStatId());
        stat.setHotelId(hotelId);
        stat.setStatMonth(month);
        stat.setCheckinCount(0);
        stat.setBookingCount(0);
        stat.setTotalAmount(0.0);
        stat.setOccupancyRate(0.0);
        return stat;
    }

    private void updateOccupancyRate(HotelStat stat) {
        Hotel hotel = hotelRepository.findById(stat.getHotelId()).orElse(null);
        if (hotel != null && hotel.getHotelRooms() != null && hotel.getHotelRooms() > 0) {
            int totalRoomNights = hotel.getHotelRooms() * LocalDate.now().lengthOfMonth();
            if (totalRoomNights > 0) {
                double rate = (double) stat.getCheckinCount() / totalRoomNights;
                stat.setOccupancyRate(Math.min(rate, 1.0));
            }
        }
    }

    public HotelStat getHotelStats(String hotelId, String month) {
        Optional<HotelStat> stat = hotelStatRepository.findByHotelIdAndStatMonth(hotelId, month);
        if (stat.isPresent()) {
            return stat.get();
        }

        HotelStat liveStat = new HotelStat();
        liveStat.setHotelId(hotelId);
        liveStat.setStatMonth(month);

        Long bookingCount = bookingRepository.countByHotelIdAndMonth(hotelId, month);
        Long checkinCount = checkInRepository.countByHotelIdAndMonth(hotelId, month);
        Double totalAmount = settlementRepository.sumTotalAmountByHotelIdAndMonth(hotelId, month);

        liveStat.setBookingCount(bookingCount != null ? bookingCount.intValue() : 0);
        liveStat.setCheckinCount(checkinCount != null ? checkinCount.intValue() : 0);
        liveStat.setTotalAmount(totalAmount != null ? totalAmount : 0.0);

        Hotel hotel = hotelRepository.findById(hotelId).orElse(null);
        if (hotel != null && hotel.getHotelRooms() != null && hotel.getHotelRooms() > 0) {
            int year = Integer.parseInt(month.substring(0, 4));
            int m = Integer.parseInt(month.substring(5, 7));
            int daysInMonth = LocalDate.of(year, m, 1).lengthOfMonth();
            int totalRoomNights = hotel.getHotelRooms() * daysInMonth;
            if (totalRoomNights > 0 && checkinCount != null) {
                liveStat.setOccupancyRate(Math.min((double) checkinCount / totalRoomNights, 1.0));
            } else {
                liveStat.setOccupancyRate(0.0);
            }
        } else {
            liveStat.setOccupancyRate(0.0);
        }

        return liveStat;
    }

    public List<HotelStat> getHotelStatsByHotel(String hotelId) {
        return hotelStatRepository.findByHotelId(hotelId);
    }
}
