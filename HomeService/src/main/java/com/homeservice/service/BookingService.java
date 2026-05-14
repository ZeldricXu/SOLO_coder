package com.homeservice.service;

import com.homeservice.dto.BookingCreateRequest;
import com.homeservice.dto.BookingResponse;
import com.homeservice.entity.Booking;
import com.homeservice.entity.Staff;
import com.homeservice.enums.BookingStatus;
import com.homeservice.enums.StaffStatus;
import com.homeservice.exception.BusinessException;
import com.homeservice.exception.ResourceNotFoundException;
import com.homeservice.repository.BookingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BookingService {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private StaffService staffService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ServiceHistoryService serviceHistoryService;

    @Autowired
    private AnalyticsService analyticsService;

    private final AtomicLong bookingCounter = new AtomicLong(0);

    @Transactional
    public BookingResponse createBooking(BookingCreateRequest request) {
        customerService.validateCustomerStatus(request.getCustomerId());
        staffService.validateStaffAvailability(request.getStaffId());
        Staff staff = staffService.getStaffById(request.getStaffId());
        checkBookingConflict(request.getStaffId(), request.getServiceTime(), request.getServiceDuration());
        double bookingAmount = calculateBookingAmount(staff.getStaffPrice(), request.getServiceDuration());
        String bookingId = "booking_" + String.format("%03d", bookingCounter.incrementAndGet());
        Booking booking = new Booking(
            bookingId,
            request.getStaffId(),
            request.getCustomerId(),
            staff.getStaffType(),
            request.getServiceTime(),
            request.getServiceDuration(),
            bookingAmount
        );
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);
        staffService.updateStaffStatus(request.getStaffId(), StaffStatus.BOOKED);
        staffService.incrementBookingCount(request.getStaffId());
        customerService.incrementBookingCount(request.getCustomerId());
        analyticsService.incrementBookingCount();
        serviceHistoryService.recordBookingHistory(
            "CREATE",
            "Booking created successfully for staff: " + request.getStaffId(),
            bookingId,
            request.getStaffId(),
            request.getCustomerId()
        );
        return new BookingResponse(bookingId, BookingStatus.CONFIRMED.getValue());
    }

    public List<Booking> getAllBookings() {
        return bookingRepository.findAll();
    }

    public Booking getBookingById(String bookingId) {
        return bookingRepository.findByBookingId(bookingId)
            .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
    }

    public List<Booking> getBookingsByStaff(String staffId) {
        return bookingRepository.findByStaffId(staffId);
    }

    public List<Booking> getBookingsByCustomer(String customerId) {
        return bookingRepository.findByCustomerId(customerId);
    }

    @Transactional
    public Booking updateBookingStatus(String bookingId, BookingStatus status) {
        Booking booking = getBookingById(bookingId);
        booking.setBookingStatus(status);
        if (status == BookingStatus.COMPLETED) {
            staffService.updateStaffStatus(booking.getStaffId(), StaffStatus.AVAILABLE);
        }
        Booking saved = bookingRepository.save(booking);
        serviceHistoryService.recordBookingHistory(
            "STATUS_UPDATE",
            "Booking status updated to: " + status.getValue(),
            bookingId,
            booking.getStaffId(),
            booking.getCustomerId()
        );
        return saved;
    }

    public void markAsReviewed(String bookingId) {
        Booking booking = getBookingById(bookingId);
        booking.setIsReviewed(true);
        bookingRepository.save(booking);
    }

    public void markAsSettled(String bookingId) {
        Booking booking = getBookingById(bookingId);
        booking.setIsSettled(true);
        bookingRepository.save(booking);
    }

    private void checkBookingConflict(String staffId, java.time.Instant serviceTime, int duration) {
        java.time.Instant startTime = serviceTime;
        java.time.Instant endTime = serviceTime.plus(Duration.ofHours(duration));
        List<Booking> conflictingBookings = bookingRepository.findConflictingBookings(
            staffId, BookingStatus.CONFIRMED, startTime, endTime
        );
        if (!conflictingBookings.isEmpty()) {
            throw new BusinessException("Staff has conflicting booking at this time");
        }
    }

    private double calculateBookingAmount(double pricePerHour, int duration) {
        return pricePerHour * duration;
    }
}
