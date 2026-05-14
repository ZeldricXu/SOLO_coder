package com.eventticket.service;

import com.eventticket.dto.StatisticsResponse;
import com.eventticket.entity.Statistics;
import com.eventticket.repository.*;
import com.eventticket.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Service
public class AnalyticsService {

    @Autowired
    private StatisticsRepository statisticsRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private VerificationRepository verificationRepository;

    @Autowired
    private ChangeRecordRepository changeRecordRepository;

    @Transactional
    public Statistics updateMonthlyStatistics() {
        String statMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        
        LocalDate firstDayOfMonth = YearMonth.now().atDay(1);
        LocalDate lastDayOfMonth = YearMonth.now().atEndOfMonth();
        LocalDateTime startOfMonth = firstDayOfMonth.atStartOfDay();
        LocalDateTime endOfMonth = lastDayOfMonth.atTime(23, 59, 59);

        long eventCount = eventRepository.countEventsByDateRange(startOfMonth, endOfMonth);
        long ticketCount = ticketRepository.countTicketsByDateRange(startOfMonth, endOfMonth);
        Long totalAmount = ticketRepository.sumConfirmedTicketPriceByDateRange(startOfMonth, endOfMonth);
        long admissionCount = ticketRepository.countUsedTicketsByDateRange(startOfMonth, endOfMonth);

        Statistics statistics = statisticsRepository.findByStatMonth(statMonth).orElse(new Statistics());
        statistics.setStatMonth(statMonth);
        statistics.setEventCount((int) eventCount);
        statistics.setTicketCount((int) ticketCount);
        statistics.setTotalAmount(totalAmount != null ? totalAmount : 0L);
        statistics.setAdmissionCount((int) admissionCount);
        
        if (statistics.getStatId() == null) {
            statistics.setStatId(IdGenerator.generateStatId());
        }
        
        return statisticsRepository.save(statistics);
    }

    @Transactional(readOnly = true)
    public StatisticsResponse getMonthlyStatistics() {
        String statMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return getStatisticsByMonth(statMonth);
    }

    @Transactional(readOnly = true)
    public StatisticsResponse getStatisticsByMonth(String statMonth) {
        Optional<Statistics> statistics = statisticsRepository.findByStatMonth(statMonth);
        
        StatisticsResponse response = new StatisticsResponse();
        response.setStatMonth(statMonth);
        
        if (statistics.isPresent()) {
            Statistics stat = statistics.get();
            response.setEventCount(stat.getEventCount());
            response.setTicketCount(stat.getTicketCount());
            response.setTotalAmount(stat.getTotalAmount());
            response.setAdmissionCount(stat.getAdmissionCount());
        } else {
            response.setEventCount(0);
            response.setTicketCount(0);
            response.setTotalAmount(0L);
            response.setAdmissionCount(0);
        }
        
        long refundCount = changeRecordRepository.countChangeRecordsByTypeAndStatus("refund", "approved");
        response.setRefundCount(refundCount);
        
        return response;
    }

    @Transactional(readOnly = true)
    public StatisticsResponse getEventStatistics(String eventId) {
        StatisticsResponse response = new StatisticsResponse();
        
        long confirmedCount = ticketRepository.countTicketsByEventIdAndStatus(
            eventId, java.util.Arrays.asList("confirmed", "used")
        );
        Long totalAmount = ticketRepository.sumTicketPriceByEventId(eventId);
        
        response.setTicketCount((int) confirmedCount);
        response.setTotalAmount(totalAmount != null ? totalAmount : 0L);
        
        return response;
    }
}
