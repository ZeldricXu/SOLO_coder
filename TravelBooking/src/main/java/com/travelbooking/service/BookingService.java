package com.travelbooking.service;

import com.travelbooking.dto.CreateBookingRequest;
import com.travelbooking.dto.CreateBookingResponse;
import com.travelbooking.exception.BusinessException;
import com.travelbooking.model.*;
import com.travelbooking.repository.BookingRepository;
import com.travelbooking.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final RouteService routeService;
    private final TouristService touristService;
    private final TeamService teamService;
    private final ItineraryService itineraryService;
    private final AnalyticsService analyticsService;
    private final HistoryService historyService;
    private final DistributedLockService lockService;

    @Transactional
    public CreateBookingResponse createBooking(CreateBookingRequest request) {
        return createBooking(request, DistributedLockService.BookingUrgency.NORMAL);
    }

    @Transactional
    public CreateBookingResponse createBooking(CreateBookingRequest request, DistributedLockService.BookingUrgency urgency) {
        String tempBookingId = "temp_" + IdGenerator.generateBookingId();

        Route route = routeService.getRouteById(request.getRouteId())
                .orElseThrow(() -> new BusinessException(404, "线路不存在"));

        if ("closed".equals(route.getRouteStatus())) {
            throw new BusinessException(400, "线路已关闭");
        }

        if (route.getRouteAvailable() <= 0) {
            throw new BusinessException(400, "名额已满");
        }

        if (route.getRouteAvailable() < request.getBookingCount()) {
            throw new BusinessException(400, "名额不足");
        }

        boolean lockAcquired = lockService.acquireLock(route.getRouteId(), tempBookingId, urgency);
        if (!lockAcquired) {
            throw new BusinessException(409, "获取锁失败，线路正在被其他预订占用");
        }

        try {
            Tourist tourist = touristService.findOrCreateTourist(
                    request.getTouristName(),
                    request.getTouristPhone(),
                    request.getTouristIdType(),
                    request.getTouristIdNumber()
            );

            Booking booking = new Booking();
            booking.setBookingId(IdGenerator.generateBookingId());
            booking.setRouteId(route.getRouteId());
            booking.setTouristId(tourist.getTouristId());
            booking.setBookingCount(request.getBookingCount());

            BigDecimal amount = route.getRoutePrice().multiply(new BigDecimal(request.getBookingCount()));
            booking.setBookingAmount(amount);
            booking.setBookingStatus("confirmed");
            booking.setBookingTime(Instant.now());
            booking.setConfirmedAt(Instant.now());

            Booking savedBooking = bookingRepository.save(booking);

            routeService.decreaseQuota(route.getRouteId(), request.getBookingCount());

            Team team = teamService.assignTeam();
            if (team == null) {
                throw new BusinessException(400, "团队不足");
            }

            Itinerary itinerary = itineraryService.createItinerary(savedBooking, route, team);

            analyticsService.updateBookingStatistics(amount, request.getBookingCount());

            historyService.recordHistory("booking", savedBooking.getBookingId(),
                    "create", "创建预订成功，预订ID: " + savedBooking.getBookingId());

            return CreateBookingResponse.builder()
                    .bookingId(savedBooking.getBookingId())
                    .status(savedBooking.getBookingStatus())
                    .build();
        } catch (Exception e) {
            throw e;
        } finally {
            lockService.releaseLock(route.getRouteId(), tempBookingId);
        }
    }

    @Transactional
    public boolean cancelBookingAndRestoreQuota(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(404, "预订不存在"));

        if ("cancelled".equals(booking.getBookingStatus())) {
            throw new BusinessException(400, "预订已取消");
        }

        Route route = routeService.getRouteById(booking.getRouteId())
                .orElseThrow(() -> new BusinessException(404, "关联线路不存在"));

        booking.setBookingStatus("cancelled");
        bookingRepository.save(booking);

        int restoredQuota = route.getRouteAvailable() + booking.getBookingCount();
        route.setRouteAvailable(restoredQuota);

        if ("full".equals(route.getRouteStatus()) && restoredQuota > 0) {
            route.setRouteStatus("available");
        }

        routeService.updateRoute(route.getRouteId(), route);

        historyService.recordHistory("booking", bookingId,
                "cancel", "取消预订并恢复名额，恢复数量: " + booking.getBookingCount());

        return true;
    }

    public List<Booking> getAllBookings() {
        return bookingRepository.findAll();
    }

    public Optional<Booking> getBookingById(String bookingId) {
        return bookingRepository.findById(bookingId);
    }

    public List<Booking> getBookingsByTouristId(String touristId) {
        return bookingRepository.findByTouristId(touristId);
    }

    public List<Booking> getBookingsByRouteId(String routeId) {
        return bookingRepository.findByRouteId(routeId);
    }

    @Transactional
    public Booking updateBookingStatus(String bookingId, String status) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(404, "预订不存在"));
        booking.setBookingStatus(status);
        return bookingRepository.save(booking);
    }

    @Transactional
    public Booking updateBooking(String bookingId, Booking booking) {
        Booking existing = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(404, "预订不存在"));

        if (booking.getBookingStatus() != null) {
            existing.setBookingStatus(booking.getBookingStatus());
        }
        if (booking.getBookingCount() != null) {
            existing.setBookingCount(booking.getBookingCount());
        }
        if (booking.getBookingAmount() != null) {
            existing.setBookingAmount(booking.getBookingAmount());
        }

        return bookingRepository.save(existing);
    }

    public void deleteBooking(String bookingId) {
        bookingRepository.deleteById(bookingId);
    }

    @Transactional
    public boolean completeSettlement(String bookingId, BigDecimal settlementAmount) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(404, "预订不存在"));
        
        booking.setBookingStatus("settled");
        bookingRepository.save(booking);
        
        historyService.recordHistory("booking", bookingId,
                "settle", "结算完成，金额: " + settlementAmount);
        
        return true;
    }
}
