package com.servicedesk.service;

import com.servicedesk.dto.SatisfactionRequest;
import com.servicedesk.entity.Satisfaction;
import com.servicedesk.entity.Ticket;
import com.servicedesk.repository.SatisfactionRepository;
import com.servicedesk.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SatisfactionService {

    private final SatisfactionRepository satisfactionRepository;
    private final TicketRepository ticketRepository;
    private final StatisticsService statisticsService;

    @Transactional
    public Satisfaction submitSatisfaction(SatisfactionRequest request) {
        Optional<Ticket> ticketOpt = ticketRepository.findById(request.getTicketId());
        if (ticketOpt.isEmpty()) {
            throw new IllegalArgumentException("工单不存在: " + request.getTicketId());
        }

        Ticket ticket = ticketOpt.get();
        String currentStatus = ticket.getTicketStatus();

        if (!StatusTrackingService.STATUS_RESOLVED.equals(currentStatus) &&
            !StatusTrackingService.STATUS_CLOSED.equals(currentStatus)) {
            throw new IllegalStateException("工单尚未完成，无法提交满意度评价");
        }

        if (satisfactionRepository.existsByTicketId(request.getTicketId())) {
            throw new IllegalStateException("该工单已提交过满意度评价");
        }

        String customerId = request.getCustomerId() != null ? request.getCustomerId() : ticket.getCustomerId();

        Satisfaction satisfaction = new Satisfaction();
        satisfaction.setSatisfactionId("satisfaction_" + UUID.randomUUID().toString().substring(0, 8));
        satisfaction.setTicketId(request.getTicketId());
        satisfaction.setCustomerId(customerId);
        satisfaction.setSatisfactionScore(request.getSatisfactionScore());
        satisfaction.setSatisfactionComment(request.getSatisfactionComment());
        satisfaction.setEvaluatedAt(Instant.now());

        Satisfaction savedSatisfaction = satisfactionRepository.save(satisfaction);
        statisticsService.updateSatisfactionRate();
        log.info("满意度评价已保存: 工单 {}, 评分 {}", request.getTicketId(), request.getSatisfactionScore());
        return savedSatisfaction;
    }

    public Optional<Satisfaction> getSatisfactionByTicketId(String ticketId) {
        return satisfactionRepository.findByTicketId(ticketId);
    }

    public Optional<Satisfaction> getSatisfactionById(String satisfactionId) {
        return satisfactionRepository.findById(satisfactionId);
    }

    public Double getAverageSatisfactionScore() {
        Double avg = satisfactionRepository.findAvgSatisfactionScore();
        return avg != null ? avg : 0.0;
    }
}
