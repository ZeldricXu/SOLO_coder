package com.movie.service;

import com.movie.dto.SeatQueryResponse;
import com.movie.entity.Seat;
import com.movie.entity.User;
import com.movie.exception.MovieException;
import com.movie.lock.SeatLockManager;
import com.movie.repository.SeatRepository;
import com.movie.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SeatService {

    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_LOCKED = "locked";
    public static final String STATUS_SOLD = "sold";
    public static final String STATUS_SELECTED = "selected";

    public static final String LEVEL_VIP = SeatLockManager.LEVEL_VIP;
    public static final String LEVEL_NORMAL = SeatLockManager.LEVEL_NORMAL;

    public static final int LOCK_TIMEOUT_SECONDS_NORMAL = SeatLockManager.LOCK_TIMEOUT_SECONDS_NORMAL;
    public static final int LOCK_TIMEOUT_SECONDS_VIP = SeatLockManager.LOCK_TIMEOUT_SECONDS_VIP;

    public static final int LOCK_WAIT_MILLIS = 3000;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private SeatLockManager seatLockManager;

    public List<Seat> getSeatsByScheduleId(String scheduleId) {
        return seatRepository.findByScheduleId(scheduleId);
    }

    public List<SeatQueryResponse> getSeatResponsesByScheduleId(String scheduleId) {
        List<Seat> seats = seatRepository.findByScheduleId(scheduleId);
        return seats.stream().map(this::toResponse).collect(Collectors.toList());
    }

    private SeatQueryResponse toResponse(Seat seat) {
        SeatQueryResponse response = new SeatQueryResponse();
        response.setSeatId(seat.getSeatId());
        response.setSeatNumber(seat.getSeatNumber());
        response.setSeatRow(seat.getSeatRow());
        response.setSeatColumn(seat.getSeatColumn());
        response.setSeatType(seat.getSeatType());
        response.setSeatStatus(seat.getSeatStatus());
        response.setSeatPrice(seat.getSeatPrice());
        return response;
    }

    public Optional<Seat> getSeatById(String seatId) {
        return seatRepository.findBySeatId(seatId);
    }

    public Seat getSeatOrThrow(String seatId) {
        return seatRepository.findBySeatId(seatId)
                .orElseThrow(() -> new MovieException(404, "座位不存在: " + seatId));
    }

    public List<Seat> getSeatsByIds(List<String> seatIds) {
        return seatRepository.findAllById(seatIds);
    }

    public int getLockTimeoutSeconds(User user) {
        return seatLockManager.getLockTimeoutSeconds(user);
    }

    public int getLockTimeoutSeconds(String userLevel) {
        return seatLockManager.getLockTimeoutSeconds(userLevel);
    }

    public boolean tryAcquireSeatLock(String seatId, String userId, User user) {
        return seatLockManager.tryAcquireLock(seatId, userId, user);
    }

    public boolean tryAcquireSeatLock(String seatId, String userId, int timeoutSeconds) {
        return seatLockManager.tryAcquireLock(seatId, userId, timeoutSeconds);
    }

    public SeatLockManager.LockResult acquireSeatLockWithWait(String seatId, String userId, User user) {
        return seatLockManager.acquireLockWithWait(seatId, userId, user, LOCK_WAIT_MILLIS);
    }

    public SeatLockManager.LockResult acquireSeatLockWithWait(String seatId, String userId, User user, long waitMillis) {
        return seatLockManager.acquireLockWithWait(seatId, userId, user, waitMillis);
    }

    public boolean releaseSeatLock(String seatId, String userId) {
        return seatLockManager.releaseLock(seatId, userId);
    }

    public boolean isSeatLockAvailable(String seatId) {
        return seatLockManager.isLockAvailable(seatId);
    }

    public String getSeatLockHolder(String seatId) {
        return seatLockManager.getLockHolder(seatId);
    }

    public boolean isSeatLockExpired(String seatId) {
        return seatLockManager.isLockExpired(seatId);
    }

    public void releaseExpiredSeatLocks() {
        seatLockManager.releaseExpiredLocks();
    }

    public int getLockedSeatCount() {
        return seatLockManager.getLockedSeatCount();
    }

    @Transactional
    public List<Seat> initializeSeats(String scheduleId, int rowCount, int columnCount, BigDecimal pricePerSeat) {
        List<Seat> seats = new ArrayList<>();
        for (int row = 1; row <= rowCount; row++) {
            for (int col = 1; col <= columnCount; col++) {
                Seat seat = new Seat();
                seat.setSeatId(IdGenerator.generateSeatId());
                seat.setScheduleId(scheduleId);
                seat.setSeatRow(row);
                seat.setSeatColumn(col);
                seat.setSeatNumber(row + "-" + col);
                seat.setSeatType("standard");
                seat.setSeatStatus(STATUS_AVAILABLE);
                seat.setSeatPrice(pricePerSeat);
                seats.add(seat);
            }
        }
        return seatRepository.saveAll(seats);
    }

    public boolean acquireDistributedLock(String seatId, User user) {
        return seatLockManager.tryAcquireLock(seatId, user != null ? user.getUserId() : null, user);
    }

    public void releaseDistributedLock(String seatId) {
        seatLockManager.releaseLock(seatId, null);
    }

    @Transactional
    public void lockSeats(List<String> seatIds, String userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Seat> seats = seatRepository.findAllById(seatIds);
        
        for (String seatId : seatIds) {
            boolean locked = seatLockManager.tryAcquireLock(seatId, userId, LOCK_TIMEOUT_SECONDS_NORMAL);
            if (!locked) {
                throw new MovieException(400, "座位锁定失败: " + seatId);
            }
        }

        for (Seat seat : seats) {
            if (!STATUS_AVAILABLE.equals(seat.getSeatStatus())) {
                for (String seatId : seatIds) {
                    seatLockManager.releaseLock(seatId, userId);
                }
                throw new MovieException(400, "座位不可用: " + seat.getSeatNumber());
            }
            seat.setSeatStatus(STATUS_LOCKED);
            seat.setLockUserId(userId);
            seat.setLockTime(now);
        }
        seatRepository.saveAll(seats);
    }

    @Transactional
    public void lockSeatsWithUserLevel(List<String> seatIds, User user) {
        int timeoutSeconds = getLockTimeoutSeconds(user);
        String userId = user != null ? user.getUserId() : null;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lockExpireTime = now.plusSeconds(timeoutSeconds);

        for (String seatId : seatIds) {
            boolean locked = seatLockManager.tryAcquireLock(seatId, userId, timeoutSeconds);
            if (!locked) {
                throw new MovieException(400, "座位已被其他用户锁定，请稍后重试");
            }
        }

        try {
            List<Seat> seats = seatRepository.findAllById(seatIds);
            for (Seat seat : seats) {
                if (!STATUS_AVAILABLE.equals(seat.getSeatStatus())) {
                    if (STATUS_LOCKED.equals(seat.getSeatStatus()) && seat.getLockTime() != null) {
                        int existingTimeout = getLockTimeoutSeconds(seat.getLockUserId() != null ?
                                LEVEL_NORMAL : LEVEL_NORMAL);
                        if (seat.getLockTime().plusSeconds(existingTimeout).isAfter(now)) {
                            throw new MovieException(400, "座位已被锁定: " + seat.getSeatNumber());
                        }
                    } else {
                        throw new MovieException(400, "座位不可用: " + seat.getSeatNumber());
                    }
                }
                seat.setSeatStatus(STATUS_LOCKED);
                seat.setLockUserId(userId);
                seat.setLockTime(now);
            }
            seatRepository.saveAll(seats);
        } catch (Exception e) {
            for (String seatId : seatIds) {
                seatLockManager.releaseLock(seatId, userId);
            }
            throw e;
        }
    }

    @Transactional
    public void releaseLock(List<String> seatIds) {
        List<Seat> seats = seatRepository.findAllById(seatIds);
        for (Seat seat : seats) {
            if (STATUS_LOCKED.equals(seat.getSeatStatus())) {
                seat.setSeatStatus(STATUS_AVAILABLE);
                seat.setLockUserId(null);
                seat.setLockTime(null);
            }
            seatLockManager.releaseLock(seat.getSeatId(), seat.getLockUserId());
        }
        seatRepository.saveAll(seats);
    }

    @Transactional
    public void selectSeats(List<String> seatIds, String userId) {
        List<Seat> seats = seatRepository.findAllById(seatIds);
        for (Seat seat : seats) {
            if (!STATUS_LOCKED.equals(seat.getSeatStatus())) {
                throw new MovieException(400, "座位未锁定，无法选择: " + seat.getSeatNumber());
            }
            if (userId != null && !userId.equals(seat.getLockUserId())) {
                throw new MovieException(400, "无权限操作此座位: " + seat.getSeatNumber());
            }
            seat.setSeatStatus(STATUS_SELECTED);
        }
        seatRepository.saveAll(seats);
    }

    @Transactional
    public void sellSeats(List<String> seatIds, String ticketId) {
        List<Seat> seats = seatRepository.findAllById(seatIds);
        for (Seat seat : seats) {
            if (STATUS_LOCKED.equals(seat.getSeatStatus()) || STATUS_SELECTED.equals(seat.getSeatStatus())) {
                seat.setSeatStatus(STATUS_SOLD);
                seat.setTicketId(ticketId);
                seat.setLockUserId(null);
                seat.setLockTime(null);
                seatLockManager.releaseLock(seat.getSeatId(), null);
            } else {
                throw new MovieException(400, "座位状态不正确，无法售出: " + seat.getSeatNumber());
            }
        }
        seatRepository.saveAll(seats);
    }

    @Transactional
    public void releaseSoldSeats(List<String> seatIds) {
        List<Seat> seats = seatRepository.findAllById(seatIds);
        for (Seat seat : seats) {
            seat.setSeatStatus(STATUS_AVAILABLE);
            seat.setTicketId(null);
        }
        seatRepository.saveAll(seats);
    }

    public void validateSeatsAvailable(List<Seat> seats) {
        for (Seat seat : seats) {
            if (STATUS_SOLD.equals(seat.getSeatStatus())) {
                throw new MovieException(400, "座位已售出: " + seat.getSeatNumber());
            }
            if (STATUS_LOCKED.equals(seat.getSeatStatus())) {
                if (seat.getLockTime() != null) {
                    int timeout = getLockTimeoutSeconds(seat.getLockUserId() != null ?
                            LEVEL_NORMAL : LEVEL_NORMAL);
                    if (seat.getLockTime().plusSeconds(timeout).isAfter(LocalDateTime.now())) {
                        throw new MovieException(400, "座位已被锁定: " + seat.getSeatNumber());
                    }
                } else {
                    throw new MovieException(400, "座位已被锁定: " + seat.getSeatNumber());
                }
            }
            if (STATUS_SELECTED.equals(seat.getSeatStatus())) {
                throw new MovieException(400, "座位已被选择: " + seat.getSeatNumber());
            }
            if (!STATUS_AVAILABLE.equals(seat.getSeatStatus())) {
                throw new MovieException(400, "座位不可选: " + seat.getSeatNumber());
            }
        }
    }

    public boolean isLockExpired(Seat seat, LocalDateTime currentTime, int timeoutSeconds) {
        if (seat.getLockTime() == null) {
            return true;
        }
        return seat.getLockTime().plusSeconds(timeoutSeconds).isBefore(currentTime);
    }

    public boolean isLockExpired(Seat seat, User user) {
        return isLockExpired(seat, LocalDateTime.now(), getLockTimeoutSeconds(user));
    }

    public BigDecimal calculateTotalPrice(List<Seat> seats) {
        BigDecimal total = BigDecimal.ZERO;
        for (Seat seat : seats) {
            total = total.add(seat.getSeatPrice() != null ? seat.getSeatPrice() : BigDecimal.ZERO);
        }
        return total;
    }

    @Transactional
    public void transitionSeatToLocked(String seatId, String userId) {
        Seat seat = getSeatOrThrow(seatId);
        if (!STATUS_AVAILABLE.equals(seat.getSeatStatus())) {
            throw new MovieException(400, "座位不是空闲状态: " + seat.getSeatNumber());
        }
        boolean locked = seatLockManager.tryAcquireLock(seatId, userId, LOCK_TIMEOUT_SECONDS_NORMAL);
        if (!locked) {
            throw new MovieException(400, "座位锁定失败: " + seat.getSeatNumber());
        }
        seat.setSeatStatus(STATUS_LOCKED);
        seat.setLockUserId(userId);
        seat.setLockTime(LocalDateTime.now());
        seatRepository.save(seat);
    }

    @Transactional
    public void transitionSeatToSelected(String seatId) {
        Seat seat = getSeatOrThrow(seatId);
        if (!STATUS_LOCKED.equals(seat.getSeatStatus())) {
            throw new MovieException(400, "座位不是锁定状态: " + seat.getSeatNumber());
        }
        seat.setSeatStatus(STATUS_SELECTED);
        seatRepository.save(seat);
    }

    @Transactional
    public void transitionSeatToSold(String seatId, String ticketId) {
        Seat seat = getSeatOrThrow(seatId);
        if (!STATUS_LOCKED.equals(seat.getSeatStatus()) && !STATUS_SELECTED.equals(seat.getSeatStatus())) {
            throw new MovieException(400, "座位状态不正确，无法售出: " + seat.getSeatNumber());
        }
        seat.setSeatStatus(STATUS_SOLD);
        seat.setTicketId(ticketId);
        seat.setLockUserId(null);
        seat.setLockTime(null);
        seatLockManager.releaseLock(seatId, null);
        seatRepository.save(seat);
    }

    @Transactional
    public void transitionSeatToAvailable(String seatId) {
        Seat seat = getSeatOrThrow(seatId);
        seat.setSeatStatus(STATUS_AVAILABLE);
        seat.setLockUserId(null);
        seat.setLockTime(null);
        seat.setTicketId(null);
        seatLockManager.releaseLock(seatId, null);
        seatRepository.save(seat);
    }

    public boolean exists(String seatId) {
        return seatRepository.existsBySeatId(seatId);
    }
}
