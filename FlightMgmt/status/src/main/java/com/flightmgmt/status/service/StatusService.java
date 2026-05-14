package com.flightmgmt.status.service;

import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.common.util.IdGenerator;
import com.flightmgmt.common.util.NotificationQueueManager;
import com.flightmgmt.flight.service.FlightService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatusService {
    private FlightService flightService = new FlightService();
    private NotificationQueueManager queueManager = NotificationQueueManager.getInstance();
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2);

    public FlightStatus updateFlightStatus(String flightId, String statusType, String detail) {
        Flight flight = DataStore.getFlight(flightId);
        if (flight == null) {
            return null;
        }

        FlightStatus status = new FlightStatus();
        status.setStatusId(IdGenerator.generateStatusId());
        status.setFlightId(flightId);
        status.setStatusType(statusType);
        status.setStatusDetail(detail);
        status.setStatusTime(LocalDateTime.now());

        DataStore.addFlightStatus(status);

        if ("delay".equalsIgnoreCase(statusType)) {
            flightService.updateFlightStatus(flightId, "delayed");
            submitAsyncNotification(flightId, statusType, "delay", "航班延误通知", detail);
        } else if ("cancelled".equalsIgnoreCase(statusType)) {
            flightService.updateFlightStatus(flightId, "cancelled");
            submitAsyncNotification(flightId, statusType, "cancellation", "航班取消通知", detail);
            updateBookingsToRefunded(flightId);
        } else if ("on_time".equalsIgnoreCase(statusType)) {
            flightService.updateFlightStatus(flightId, "on_time");
            submitAsyncNotification(flightId, statusType, "normal", "航班状态正常通知", detail);
        }

        return status;
    }

    private void submitAsyncNotification(String flightId, String statusType, String notificationType, 
                                          String title, String detail) {
        asyncExecutor.submit(() -> {
            try {
                List<Booking> bookings = DataStore.getBookings().values().stream()
                    .filter(b -> b.getFlightId() != null && b.getFlightId().equals(flightId))
                    .filter(b -> "confirmed".equalsIgnoreCase(b.getBookingStatus()))
                    .collect(java.util.stream.Collectors.toList());

                for (Booking booking : bookings) {
                    Passenger passenger = DataStore.getPassenger(booking.getPassengerId());
                    if (passenger != null) {
                        NotificationTask task = NotificationTask.create(
                            booking.getBookingId(),
                            passenger.getPassengerId(),
                            flightId,
                            notificationType,
                            title,
                            buildNotificationContent(notificationType, detail, passenger),
                            3
                        );
                        
                        if (passenger.getPassengerPhone() != null) {
                            task.setPassengerPhone(passenger.getPassengerPhone());
                        }

                        queueManager.submitTask(task);
                        
                        System.out.println("异步通知任务已提交: " + title + 
                            " - 乘客: " + passenger.getPassengerName());
                    }
                }
            } catch (Exception e) {
                System.err.println("异步通知处理出错: " + e.getMessage());
            }
        });
    }

    private String buildNotificationContent(String notificationType, String detail, Passenger passenger) {
        StringBuilder content = new StringBuilder();
        content.append("尊敬的 ").append(passenger.getPassengerName()).append("，\n");
        
        if ("delay".equals(notificationType)) {
            content.append("很抱歉通知您，您的航班已延误。\n");
        } else if ("cancellation".equals(notificationType)) {
            content.append("很抱歉通知您，您的航班已取消。\n");
            content.append("我们将为您办理全额退款。\n");
        } else if ("normal".equals(notificationType)) {
            content.append("很高兴通知您，您的航班状态正常。\n");
        }
        
        if (detail != null && !detail.isEmpty()) {
            content.append("详情: ").append(detail).append("\n");
        }
        
        content.append("如有疑问请联系客服。");
        return content.toString();
    }

    private void updateBookingsToRefunded(String flightId) {
        DataStore.getBookings().values().stream()
            .filter(b -> b.getFlightId() != null && b.getFlightId().equals(flightId))
            .filter(b -> "confirmed".equalsIgnoreCase(b.getBookingStatus()))
            .forEach(b -> b.setBookingStatus("refunded"));
    }

    private void sendDelayNotification(String flightId, String detail) {
        List<Booking> bookings = DataStore.getBookings().values().stream()
            .filter(b -> b.getFlightId() != null && b.getFlightId().equals(flightId))
            .filter(b -> "confirmed".equalsIgnoreCase(b.getBookingStatus()))
            .collect(java.util.stream.Collectors.toList());

        for (Booking booking : bookings) {
            Passenger passenger = DataStore.getPassenger(booking.getPassengerId());
            if (passenger != null) {
                System.out.println("延误通知: 航班 " + flightId + " 延误，通知乘客 " + 
                    passenger.getPassengerName() + ": " + detail);
            }
        }
    }

    private void sendCancelNotification(String flightId, String detail) {
        List<Booking> bookings = DataStore.getBookings().values().stream()
            .filter(b -> b.getFlightId() != null && b.getFlightId().equals(flightId))
            .filter(b -> "confirmed".equalsIgnoreCase(b.getBookingStatus()))
            .collect(java.util.stream.Collectors.toList());

        for (Booking booking : bookings) {
            booking.setBookingStatus("refunded");
            Passenger passenger = DataStore.getPassenger(booking.getPassengerId());
            if (passenger != null) {
                System.out.println("取消通知: 航班 " + flightId + " 取消，通知乘客 " + 
                    passenger.getPassengerName() + ": " + detail);
            }
        }
    }

    private void sendNormalNotification(String flightId, String detail) {
        List<Booking> bookings = DataStore.getBookings().values().stream()
            .filter(b -> b.getFlightId() != null && b.getFlightId().equals(flightId))
            .filter(b -> "confirmed".equalsIgnoreCase(b.getBookingStatus()))
            .collect(java.util.stream.Collectors.toList());

        for (Booking booking : bookings) {
            Passenger passenger = DataStore.getPassenger(booking.getPassengerId());
            if (passenger != null) {
                System.out.println("正常通知: 航班 " + flightId + " 状态正常，通知乘客 " + 
                    passenger.getPassengerName());
            }
        }
    }

    public void startNotificationQueue() {
        queueManager.start();
    }

    public void stopNotificationQueue() {
        queueManager.stop();
        asyncExecutor.shutdown();
    }

    public List<FlightStatus> getFlightStatusHistory(String flightId) {
        return DataStore.getStatusHistory().stream()
            .filter(s -> s.getFlightId() != null && s.getFlightId().equals(flightId))
            .collect(java.util.stream.Collectors.toList());
    }

    public FlightStatus getLatestFlightStatus(String flightId) {
        List<FlightStatus> history = getFlightStatusHistory(flightId);
        if (history.isEmpty()) {
            return null;
        }
        return history.get(history.size() - 1);
    }
}
