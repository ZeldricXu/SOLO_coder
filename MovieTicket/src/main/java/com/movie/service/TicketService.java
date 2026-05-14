package com.movie.service;

import com.movie.dto.TicketCreateRequest;
import com.movie.dto.TicketCreateResponse;
import com.movie.entity.*;
import com.movie.exception.MovieException;
import com.movie.repository.TicketRepository;
import com.movie.scheduler.PaymentTimeoutScheduler;
import com.movie.util.IdGenerator;
import com.movie.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TicketService {

    public static final String STATUS_PENDING_SEAT = "pending_seat";
    public static final String STATUS_PENDING_PAYMENT = "pending_payment";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final int PAYMENT_TIMEOUT_SECONDS = PaymentTimeoutScheduler.PAYMENT_TIMEOUT_SECONDS;
    public static final int PAYMENT_TIMEOUT_SECONDS_VIP = PaymentTimeoutScheduler.PAYMENT_TIMEOUT_SECONDS_VIP;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private SeatService seatService;

    @Autowired
    private MovieService movieService;

    @Autowired
    private CinemaService cinemaService;

    @Autowired
    private UserService userService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private PaymentTimeoutScheduler paymentTimeoutScheduler;

    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(2);
    private final ConcurrentHashMap<String, Boolean> processedTickets = new ConcurrentHashMap<>();

    public List<Ticket> getAllTickets() {
        return ticketRepository.findAll();
    }

    public Optional<Ticket> getTicketById(String ticketId) {
        return ticketRepository.findByTicketId(ticketId);
    }

    public Ticket getTicketOrThrow(String ticketId) {
        return ticketRepository.findByTicketId(ticketId)
                .orElseThrow(() -> new MovieException(404, "票务不存在: " + ticketId));
    }

    public List<Ticket> getTicketsByUser(String userId) {
        return ticketRepository.findByUserId(userId);
    }

    public List<Ticket> getTicketsBySchedule(String scheduleId) {
        return ticketRepository.findByScheduleId(scheduleId);
    }

    public int getPaymentTimeoutSeconds(User user) {
        return paymentTimeoutScheduler.getPaymentTimeoutSeconds(user != null ? user.getUserLevel() : null);
    }

    public int getPaymentTimeoutSeconds(String userLevel) {
        return paymentTimeoutScheduler.getPaymentTimeoutSeconds(userLevel);
    }

    public int getPendingPaymentCount() {
        return paymentTimeoutScheduler.getPendingCount();
    }

    public boolean isPaymentPending(String ticketId) {
        return paymentTimeoutScheduler.isPending(ticketId);
    }

    public void forceExpirePayment(String ticketId) {
        paymentTimeoutScheduler.forceExpireTicket(ticketId);
    }

    @Transactional
    public TicketCreateResponse createTicket(TicketCreateRequest request) {
        return createTicketWithPayment(request, true);
    }

    @Transactional
    public TicketCreateResponse createTicketWithPayment(TicketCreateRequest request, boolean simulatePaymentSuccess) {
        if (request.getScheduleId() == null) {
            throw new MovieException(400, "场次ID不能为空");
        }
        if (request.getSeatIds() == null || request.getSeatIds().isEmpty()) {
            throw new MovieException(400, "座位未选择");
        }

        Schedule schedule = scheduleService.getScheduleOrThrow(request.getScheduleId());
        scheduleService.validateSchedule(schedule);

        Movie movie = movieService.getMovieOrThrow(schedule.getMovieId());
        Cinema cinema = cinemaService.getCinemaOrThrow(schedule.getCinemaId());

        User user = userService.getOrCreateUser(request.getUserId(), null, null);

        List<Seat> seats = seatService.getSeatsByIds(request.getSeatIds());
        if (seats.size() != request.getSeatIds().size()) {
            throw new MovieException(400, "部分座位不存在");
        }

        for (Seat seat : seats) {
            if (!schedule.getScheduleId().equals(seat.getScheduleId())) {
                throw new MovieException(400, "座位不属于当前场次");
            }
        }

        seatService.validateSeatsAvailable(seats);

        try {
            seatService.lockSeatsWithUserLevel(request.getSeatIds(), user);
        } catch (MovieException e) {
            throw new MovieException(409, e.getMessage());
        }

        BigDecimal totalAmount = calculateTicketAmount(seats);

        Ticket ticket = new Ticket();
        ticket.setTicketId(IdGenerator.generateTicketId());
        ticket.setScheduleId(schedule.getScheduleId());
        ticket.setUserId(user.getUserId());
        ticket.setSeatIds(request.getSeatIds());
        ticket.setSeatIdsJson(JsonUtil.toJson(request.getSeatIds()));
        ticket.setTicketAmount(totalAmount);
        ticket.setTicketStatus(STATUS_PENDING_PAYMENT);
        ticket.setTicketTime(LocalDateTime.now());
        ticket.setMovieName(movie.getMovieName());
        ticket.setCinemaName(cinema.getCinemaName());
        ticket.setScheduleDate(schedule.getScheduleDate());
        ticket.setScheduleTime(schedule.getScheduleTime());

        Ticket savedTicket = ticketRepository.save(ticket);

        historyService.recordTicketCreation(savedTicket, movie, cinema);

        paymentTimeoutScheduler.scheduleTimeout(savedTicket.getTicketId(), request.getSeatIds(), 
                user != null ? user.getUserLevel() : null);

        boolean paymentSuccess = simulatePaymentSuccess && processPayment(savedTicket);

        if (paymentSuccess) {
            confirmTicket(savedTicket, request.getSeatIds(), movie, cinema, schedule);
        } else {
            cancelPendingPayment(savedTicket, request.getSeatIds(), movie, cinema);
        }

        TicketCreateResponse response = new TicketCreateResponse();
        response.setTicketId(savedTicket.getTicketId());
        response.setScheduleId(savedTicket.getScheduleId());
        response.setUserId(savedTicket.getUserId());
        response.setSeatIds(savedTicket.getSeatIds());
        response.setTicketAmount(savedTicket.getTicketAmount());
        response.setTicketStatus(savedTicket.getTicketStatus());
        response.setTicketTime(savedTicket.getTicketTime());

        return response;
    }

    public BigDecimal calculateTicketAmount(List<Seat> seats) {
        return seatService.calculateTotalPrice(seats);
    }

    @Transactional
    public void confirmTicket(Ticket ticket, List<String> seatIds, Movie movie, Cinema cinema, Schedule schedule) {
        if (!STATUS_PENDING_PAYMENT.equals(ticket.getTicketStatus())) {
            return;
        }

        if (processedTickets.putIfAbsent(ticket.getTicketId(), true) != null) {
            return;
        }

        paymentTimeoutScheduler.markAsCompleted(ticket.getTicketId());

        ticket.setTicketStatus(STATUS_CONFIRMED);
        ticket.setConfirmedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        seatService.sellSeats(seatIds, ticket.getTicketId());
        scheduleService.decreaseAvailableSeats(schedule.getScheduleId(), seatIds.size());
        analysisService.recordTicketSale(ticket, movie, cinema);
        historyService.recordTicketStatusChange(ticket, movie, cinema,
                STATUS_PENDING_PAYMENT, STATUS_CONFIRMED, "CONFIRMED", "支付成功，票务已确认");
    }

    @Transactional
    public void cancelPendingTicket(Ticket ticket, List<String> seatIds, Movie movie, Cinema cinema) {
        cancelPendingPayment(ticket, seatIds, movie, cinema);
    }

    @Transactional
    public void cancelPendingPayment(Ticket ticket, List<String> seatIds, Movie movie, Cinema cinema) {
        if (!STATUS_PENDING_PAYMENT.equals(ticket.getTicketStatus())) {
            return;
        }

        if (processedTickets.putIfAbsent(ticket.getTicketId(), true) != null) {
            return;
        }

        paymentTimeoutScheduler.markAsCompleted(ticket.getTicketId());

        ticket.setTicketStatus(STATUS_CANCELLED);
        ticketRepository.save(ticket);

        seatService.releaseLock(seatIds);

        Schedule schedule = scheduleService.getScheduleById(ticket.getScheduleId()).orElse(null);

        historyService.recordTicketStatusChange(ticket, movie, cinema,
                STATUS_PENDING_PAYMENT, STATUS_CANCELLED, "CANCELLED", "支付超时，票务已自动取消");
    }

    private void schedulePaymentTimeout(Ticket ticket, User user, List<String> seatIds, Movie movie, Cinema cinema) {
        int timeoutSeconds = getPaymentTimeoutSeconds(user);
        String ticketId = ticket.getTicketId();

        timeoutScheduler.schedule(() -> {
            try {
                if (processedTickets.containsKey(ticketId)) {
                    return;
                }
                Optional<Ticket> ticketOpt = ticketRepository.findByTicketId(ticketId);
                if (ticketOpt.isPresent() && STATUS_PENDING_PAYMENT.equals(ticketOpt.get().getTicketStatus())) {
                    cancelPendingTicket(ticketOpt.get(), seatIds, movie, cinema);
                }
            } catch (Exception e) {
                System.err.println("支付超时处理异常: " + e.getMessage());
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    private boolean processPayment(Ticket ticket) {
        try {
            Thread.sleep(10);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean simulatePaymentFailure(Ticket ticket) {
        if (!STATUS_PENDING_PAYMENT.equals(ticket.getTicketStatus())) {
            throw new MovieException(400, "票务不是待支付状态");
        }

        Movie movie = movieService.getMovieById(ticket.getScheduleId() != null ? 
                scheduleService.getScheduleById(ticket.getScheduleId()).map(Schedule::getMovieId).orElse(null) : null).orElse(null);
        Cinema cinema = cinemaService.getCinemaById(ticket.getScheduleId() != null ?
                scheduleService.getScheduleById(ticket.getScheduleId()).map(Schedule::getCinemaId).orElse(null) : null).orElse(null);

        List<String> seatIds = JsonUtil.fromJsonStringList(ticket.getSeatIdsJson());

        cancelPendingPayment(ticket, seatIds, movie, cinema);

        return true;
    }

    @Transactional
    public Ticket cancelTicket(String ticketId) {
        Ticket ticket = getTicketOrThrow(ticketId);
        
        if (!STATUS_CONFIRMED.equals(ticket.getTicketStatus())) {
            throw new MovieException(400, "只有已确认的票务可以取消");
        }

        String oldStatus = ticket.getTicketStatus();
        ticket.setTicketStatus(STATUS_CANCELLED);
        Ticket savedTicket = ticketRepository.save(ticket);

        List<String> seatIds = JsonUtil.fromJsonStringList(ticket.getSeatIdsJson());
        if (!seatIds.isEmpty()) {
            seatService.releaseSoldSeats(seatIds);
        }

        scheduleService.increaseAvailableSeats(ticket.getScheduleId(), seatIds.size());

        Schedule schedule = scheduleService.getScheduleById(ticket.getScheduleId()).orElse(null);
        Movie movie = schedule != null ? movieService.getMovieById(schedule.getMovieId()).orElse(null) : null;
        Cinema cinema = schedule != null ? cinemaService.getCinemaById(schedule.getCinemaId()).orElse(null) : null;

        analysisService.recordTicketRefund(savedTicket, movie, cinema);
        historyService.recordTicketStatusChange(savedTicket, movie, cinema, 
                oldStatus, STATUS_CANCELLED, "CANCELLED", "用户取消票务");

        return savedTicket;
    }

    public boolean exists(String ticketId) {
        return ticketRepository.existsByTicketId(ticketId);
    }

    public void shutdown() {
        timeoutScheduler.shutdown();
    }
}
