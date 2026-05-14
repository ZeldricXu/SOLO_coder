package com.movie.scheduler;

import com.movie.entity.Movie;
import com.movie.entity.Ticket;
import com.movie.repository.TicketRepository;
import com.movie.service.CinemaService;
import com.movie.service.MovieService;
import com.movie.service.SeatService;
import com.movie.service.TicketService;
import com.movie.util.JsonUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PaymentTimeoutScheduler {

    public static final int PAYMENT_TIMEOUT_SECONDS = 300;
    public static final int PAYMENT_TIMEOUT_SECONDS_VIP = 600;
    public static final int TIMEOUT_CHECK_INTERVAL_SECONDS = 30;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private SeatService seatService;

    @Autowired
    private MovieService movieService;

    @Autowired
    private CinemaService cinemaService;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ConcurrentHashMap<String, TimeoutTask> pendingTickets = new ConcurrentHashMap<>();

    public static class TimeoutTask {
        private final String ticketId;
        private final List<String> seatIds;
        private final LocalDateTime createdAt;
        private final int timeoutSeconds;
        private volatile boolean cancelled = false;

        public TimeoutTask(String ticketId, List<String> seatIds, int timeoutSeconds) {
            this.ticketId = ticketId;
            this.seatIds = seatIds;
            this.createdAt = LocalDateTime.now();
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getTicketId() {
            return ticketId;
        }

        public List<String> getSeatIds() {
            return seatIds;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            this.cancelled = true;
        }

        public boolean isExpired(LocalDateTime now) {
            if (cancelled) {
                return false;
            }
            return createdAt.plusSeconds(timeoutSeconds).isBefore(now);
        }
    }

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(
                this::checkAndCancelExpiredTickets,
                TIMEOUT_CHECK_INTERVAL_SECONDS,
                TIMEOUT_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }

    public void scheduleTimeout(String ticketId, List<String> seatIds, String userLevel) {
        int timeoutSeconds = getPaymentTimeoutSeconds(userLevel);
        TimeoutTask task = new TimeoutTask(ticketId, seatIds, timeoutSeconds);
        pendingTickets.put(ticketId, task);
    }

    public void scheduleTimeout(String ticketId, List<String> seatIds, int timeoutSeconds) {
        TimeoutTask task = new TimeoutTask(ticketId, seatIds, timeoutSeconds);
        pendingTickets.put(ticketId, task);
    }

    public void markAsCompleted(String ticketId) {
        TimeoutTask task = pendingTickets.get(ticketId);
        if (task != null) {
            task.cancel();
        }
        pendingTickets.remove(ticketId);
    }

    public void cancelTimeout(String ticketId) {
        markAsCompleted(ticketId);
    }

    public boolean isPending(String ticketId) {
        TimeoutTask task = pendingTickets.get(ticketId);
        return task != null && !task.isCancelled();
    }

    public int getPendingCount() {
        return (int) pendingTickets.values().stream()
                .filter(t -> !t.isCancelled())
                .count();
    }

    public int getPaymentTimeoutSeconds(String userLevel) {
        if ("vip".equalsIgnoreCase(userLevel)) {
            return PAYMENT_TIMEOUT_SECONDS_VIP;
        }
        return PAYMENT_TIMEOUT_SECONDS;
    }

    private void checkAndCancelExpiredTickets() {
        LocalDateTime now = LocalDateTime.now();
        for (TimeoutTask task : pendingTickets.values()) {
            if (task.isExpired(now)) {
                cancelExpiredTicket(task);
            }
        }
    }

    private void cancelExpiredTicket(TimeoutTask task) {
        if (task.isCancelled()) {
            return;
        }

        try {
            Ticket ticket = ticketRepository.findByTicketId(task.getTicketId()).orElse(null);
            if (ticket == null) {
                task.cancel();
                pendingTickets.remove(task.getTicketId());
                return;
            }

            if (!TicketService.STATUS_PENDING_PAYMENT.equals(ticket.getTicketStatus())) {
                task.cancel();
                pendingTickets.remove(task.getTicketId());
                return;
            }

            ticket.setTicketStatus(TicketService.STATUS_CANCELLED);
            ticketRepository.save(ticket);

            List<String> seatIds = task.getSeatIds();
            if (seatIds.isEmpty() && ticket.getSeatIdsJson() != null) {
                seatIds = JsonUtil.fromJsonStringList(ticket.getSeatIdsJson());
            }

            if (!seatIds.isEmpty()) {
                seatService.releaseLock(seatIds);
            }

            task.cancel();
            pendingTickets.remove(task.getTicketId());

            System.out.println("支付超时自动取消票务: " + task.getTicketId());
        } catch (Exception e) {
            System.err.println("处理超时票务异常: " + e.getMessage());
        }
    }

    public void forceExpireTicket(String ticketId) {
        TimeoutTask task = pendingTickets.get(ticketId);
        if (task != null) {
            cancelExpiredTicket(task);
        }
    }
}
