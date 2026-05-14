package com.flightmgmt.booking.service;

import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.common.util.IdGenerator;
import com.flightmgmt.common.util.ConfigManager;
import com.flightmgmt.flight.service.FlightService;
import com.flightmgmt.passenger.service.PassengerService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BookingService {
    private FlightService flightService = new FlightService();
    private PassengerService passengerService = new PassengerService();
    private ConfigManager configManager = ConfigManager.getInstance();
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(2);
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    public Booking createBooking(String flightId, String passengerName, String passengerIdNumber, 
                                  String paymentMethod, int seats) {
        Flight flight = DataStore.getFlight(flightId);
        if (flight == null) {
            Booking failed = new Booking();
            failed.setBookingStatus("flight_not_found");
            return failed;
        }

        if ("cancelled".equalsIgnoreCase(flight.getFlightStatus())) {
            Booking failed = new Booking();
            failed.setBookingStatus("flight_unavailable");
            return failed;
        }

        if (flight.getFlightAvailable() < seats) {
            Booking failed = new Booking();
            failed.setBookingStatus("seats_insufficient");
            return failed;
        }

        Passenger passenger = passengerService.findByIdNumber(passengerIdNumber);
        if (passenger == null) {
            passenger = new Passenger();
            passenger.setPassengerName(passengerName);
            passenger.setPassengerIdNumber(passengerIdNumber);
            passenger = passengerService.createPassenger(passenger);
        }

        Booking booking = new Booking();
        booking.setBookingId(IdGenerator.generateBookingId());
        booking.setFlightId(flightId);
        booking.setPassengerId(passenger.getPassengerId());
        booking.setBookingSeats(seats);
        booking.setBookingAmount(flight.getFlightPrice() * seats);
        booking.setBookingStatus("pending_payment");
        booking.setPaymentMethod(paymentMethod);
        booking.setCreatedAt(LocalDateTime.now());

        DataStore.addBooking(booking);

        int timeoutMinutes = configManager.getPaymentTimeoutMinutes(flight.getFlightType());
        schedulePaymentTimeout(booking.getBookingId(), flightId, flight.getFlightType(), passenger, timeoutMinutes);

        boolean paymentSuccess = processPayment(booking);
        if (paymentSuccess) {
            cancelPaymentTimeout(booking.getBookingId());
            booking.setBookingStatus("confirmed");
            booking.setConfirmedAt(LocalDateTime.now());
            flightService.updateAvailableSeats(flightId, -seats);
            sendNotification(passenger, "预订确认", "您的预订已确认，预订编号: " + booking.getBookingId());
        } else {
            booking.setBookingStatus("cancelled");
            sendNotification(passenger, "预订取消", "您的预订因支付失败已取消");
        }

        return booking;
    }

    private void schedulePaymentTimeout(String bookingId, String flightId, String flightType, 
                                        Passenger passenger, int timeoutMinutes) {
        PaymentTimeoutTask timeoutTask = new PaymentTimeoutTask(bookingId, flightId, flightType, passenger, this);
        
        ScheduledFuture<?> future = timeoutScheduler.schedule(
            timeoutTask, 
            timeoutMinutes, 
            TimeUnit.MINUTES
        );
        
        timeoutTasks.put(bookingId, future);
        
        System.out.println("预订 " + bookingId + " 支付超时定时器已设置，时长: " + timeoutMinutes + " 分钟");
    }

    private void cancelPaymentTimeout(String bookingId) {
        ScheduledFuture<?> future = timeoutTasks.remove(bookingId);
        if (future != null) {
            future.cancel(false);
            System.out.println("预订 " + bookingId + " 支付超时定时器已取消");
        }
    }

    public void handlePaymentTimeout(String bookingId, String flightId, String flightType, Passenger passenger) {
        Booking booking = DataStore.getBooking(bookingId);
        if (booking == null) {
            return;
        }

        if ("pending_payment".equalsIgnoreCase(booking.getBookingStatus())) {
            booking.setBookingStatus("timeout_cancelled");
            
            int timeoutMinutes = configManager.getPaymentTimeoutMinutes(flightType);
            String flightTypeLabel = "international".equalsIgnoreCase(flightType) ? "国际航班" : "国内航班";
            
            sendNotification(passenger, "支付超时提醒", 
                "您的预订 " + bookingId + " 已超过 " + timeoutMinutes + 
                " 分钟支付时限（" + flightTypeLabel + "），预订已取消。如需预订请重新下单。");
            
            System.out.println("预订 " + bookingId + " 因支付超时已取消，航班类型: " + flightType);
        }
        
        timeoutTasks.remove(bookingId);
    }

    public int getPaymentTimeoutMinutes(String flightType) {
        return configManager.getPaymentTimeoutMinutes(flightType);
    }

    private boolean processPayment(Booking booking) {
        String method = booking.getPaymentMethod();
        if (method == null || method.isEmpty()) {
            return false;
        }
        return true;
    }

    private void sendNotification(Passenger passenger, String title, String content) {
        System.out.println("通知 [" + title + "] 发送给 " + passenger.getPassengerName() + ": " + content);
    }

    public Booking getBooking(String bookingId) {
        return DataStore.getBooking(bookingId);
    }

    public List<Booking> getAllBookings() {
        return DataStore.getBookings().values().stream().collect(Collectors.toList());
    }

    public List<Booking> getBookingsByFlight(String flightId) {
        return DataStore.getBookings().values().stream()
            .filter(b -> b.getFlightId() != null && b.getFlightId().equals(flightId))
            .collect(Collectors.toList());
    }

    public List<Booking> getBookingsByPassenger(String passengerId) {
        return DataStore.getBookings().values().stream()
            .filter(b -> b.getPassengerId() != null && b.getPassengerId().equals(passengerId))
            .collect(Collectors.toList());
    }

    public Booking updateBookingStatus(String bookingId, String status) {
        Booking booking = DataStore.getBooking(bookingId);
        if (booking != null) {
            if ("confirmed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status)) {
                cancelPaymentTimeout(bookingId);
            }
            booking.setBookingStatus(status);
        }
        return booking;
    }

    public void shutdown() {
        timeoutScheduler.shutdown();
        try {
            if (!timeoutScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutScheduler.shutdownNow();
        }
    }

    public static class PaymentTimeoutTask implements Runnable {
        private final String bookingId;
        private final String flightId;
        private final String flightType;
        private final Passenger passenger;
        private final BookingService bookingService;

        public PaymentTimeoutTask(String bookingId, String flightId, String flightType, 
                                   Passenger passenger, BookingService bookingService) {
            this.bookingId = bookingId;
            this.flightId = flightId;
            this.flightType = flightType;
            this.passenger = passenger;
            this.bookingService = bookingService;
        }

        @Override
        public void run() {
            bookingService.handlePaymentTimeout(bookingId, flightId, flightType, passenger);
        }
    }
}
