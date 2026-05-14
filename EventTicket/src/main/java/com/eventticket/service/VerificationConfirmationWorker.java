package com.eventticket.service;

import com.eventticket.entity.Seat;
import com.eventticket.entity.Ticket;
import com.eventticket.entity.VerificationConfirmationTask;
import com.eventticket.repository.SeatRepository;
import com.eventticket.repository.TicketRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class VerificationConfirmationWorker {

    @Autowired
    private VerificationQueueService queueService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private TicketHistoryService ticketHistoryService;

    @Value("${verification-queue.processing.poll-interval-ms:1000}")
    private long pollIntervalMs;

    @Value("${verification-queue.processing.max-wait-ms:30000}")
    private long maxWaitMs;

    @Value("${verification-queue.processing.batch-size:100}")
    private int batchSize;

    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @PostConstruct
    public void start() {
        running.set(true);
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(this::runWorker);
        log.info("Verification confirmation worker started");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        log.info("Verification confirmation worker stopped");
    }

    private void runWorker() {
        while (running.get()) {
            try {
                processBatch();
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in verification confirmation worker", e);
            }
        }
    }

    private void processBatch() {
        int processed = 0;
        while (processed < batchSize && running.get()) {
            VerificationConfirmationTask task = queueService.dequeueConfirmationTask(1, TimeUnit.SECONDS);
            if (task == null) {
                break;
            }
            processTask(task);
            processed++;
        }
    }

    @Transactional
    public void processTask(VerificationConfirmationTask task) {
        log.info("Processing verification confirmation task: taskId={}, ticketId={}", 
                task.getTaskId(), task.getTicketId());

        try {
            boolean confirmed = executeConfirmation(task);
            
            if (confirmed) {
                task.markSuccess();
                log.info("Verification confirmation succeeded: taskId={}, ticketId={}", 
                        task.getTaskId(), task.getTicketId());
            } else {
                handleRetryOrFail(task, "Confirmation execution failed");
            }
        } catch (Exception e) {
            log.error("Error processing verification confirmation task", e);
            handleRetryOrFail(task, e.getMessage());
        }
    }

    private boolean executeConfirmation(VerificationConfirmationTask task) {
        String ticketId = task.getTicketId();
        String seatId = task.getSeatId();

        Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) {
            log.warn("Ticket not found for confirmation: ticketId={}", ticketId);
            return true;
        }

        if ("used".equals(ticket.getTicketStatus())) {
            log.info("Ticket already confirmed: ticketId={}", ticketId);
            return true;
        }

        ticket.setTicketStatus("used");
        ticket.setUsedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        if (seatId != null) {
            Seat seat = seatRepository.findById(seatId).orElse(null);
            if (seat != null && !"admitted".equals(seat.getSeatStatus())) {
                seat.setSeatStatus("admitted");
                seat.setAdmittedAt(LocalDateTime.now());
                seatRepository.save(seat);
            }
        }

        ticketHistoryService.recordVerification(
            ticketId, 
            "验证确认成功，入场完成", 
            task.getOperator()
        );

        return true;
    }

    private void handleRetryOrFail(VerificationConfirmationTask task, String errorMessage) {
        if (task.shouldRetry()) {
            queueService.enqueueRetryTask(task);
            log.info("Scheduled retry for verification task: taskId={}, retryCount={}/{}, nextRetry={}", 
                    task.getTaskId(), task.getRetryCount() + 1, task.getMaxRetries(), task.getNextRetryTime());
        } else {
            queueService.moveToDeadLetterQueue(task, errorMessage);
            log.error("Verification confirmation task failed after max retries: taskId={}, error={}", 
                    task.getTaskId(), errorMessage);
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
