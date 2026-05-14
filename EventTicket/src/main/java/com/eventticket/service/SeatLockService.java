package com.eventticket.service;

import com.eventticket.config.TicketLockConfig;
import com.eventticket.config.TicketLockConfig.LockTimeoutConfig;
import com.eventticket.entity.Seat;
import com.eventticket.repository.SeatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SeatLockService {

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private TicketLockConfig lockConfig;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Transactional
    public Seat lockSeat(String seatId, String ticketType) {
        Seat seat = seatRepository.findByIdWithLock(seatId).orElse(null);
        if (seat == null) {
            throw new RuntimeException("座位不存在");
        }
        if (!"available".equals(seat.getSeatStatus())) {
            throw new RuntimeException("座位不可用");
        }

        int lockTimeoutSeconds = lockConfig.getLockTimeoutSeconds(ticketType);

        seat.setSeatStatus("locked");
        seat.setLockedAt(LocalDateTime.now());
        Seat lockedSeat = seatRepository.save(seat);

        String lockKey = buildLockKey(seatId);
        redisTemplate.opsForValue().set(lockKey, ticketType, lockTimeoutSeconds, TimeUnit.SECONDS);

        log.info("Seat locked: seatId={}, ticketType={}, timeout={}s", seatId, ticketType, lockTimeoutSeconds);
        return lockedSeat;
    }

    @Transactional
    public Seat lockSeatAutoAssign(String eventId, String ticketType, String preferredSection) {
        java.util.List<Seat> availableSeats;
        if (preferredSection != null && !preferredSection.isEmpty()) {
            availableSeats = seatRepository.findAvailableSeatsByEventIdAndSection(eventId, preferredSection);
        } else {
            availableSeats = seatRepository.findAvailableSeatsSorted(eventId);
        }

        if (availableSeats.isEmpty()) {
            throw new RuntimeException("没有可用座位");
        }

        for (Seat seat : availableSeats) {
            try {
                return lockSeat(seat.getSeatId(), ticketType);
            } catch (RuntimeException e) {
                log.warn("Failed to lock seat: {}, trying next...", seat.getSeatId());
            }
        }
        throw new RuntimeException("座位锁定失败，请重试");
    }

    @Transactional
    public Seat confirmSeatLock(String seatId) {
        return seatRepository.findByIdWithLock(seatId).map(seat -> {
            if ("locked".equals(seat.getSeatStatus())) {
                seat.setSeatStatus("sold");
                seat.setSoldAt(LocalDateTime.now());
                Seat confirmedSeat = seatRepository.save(seat);

                String lockKey = buildLockKey(seatId);
                redisTemplate.delete(lockKey);

                log.info("Seat lock confirmed: seatId={}", seatId);
                return confirmedSeat;
            }
            return null;
        }).orElse(null);
    }

    @Transactional
    public Seat releaseSeatLock(String seatId) {
        return seatRepository.findByIdWithLock(seatId).map(seat -> {
            if ("locked".equals(seat.getSeatStatus())) {
                seat.setSeatStatus("available");
                seat.setLockedAt(null);
                Seat releasedSeat = seatRepository.save(seat);

                String lockKey = buildLockKey(seatId);
                redisTemplate.delete(lockKey);

                log.info("Seat lock released: seatId={}", seatId);
                return releasedSeat;
            }
            return seat;
        }).orElse(null);
    }

    public int getLockTimeoutSeconds(String ticketType) {
        return lockConfig.getLockTimeoutSeconds(ticketType);
    }

    public int getPaymentTimeoutMinutes(String ticketType) {
        return lockConfig.getPaymentTimeoutMinutes(ticketType);
    }

    public int getAutoReleaseDelayMinutes(String ticketType) {
        return lockConfig.getAutoReleaseDelayMinutes(ticketType);
    }

    public LockTimeoutConfig getLockConfig(String ticketType) {
        return lockConfig.getLockConfig(ticketType);
    }

    public boolean isSeatLocked(String seatId) {
        Optional<Seat> seatOpt = seatRepository.findById(seatId);
        if (seatOpt.isPresent()) {
            return "locked".equals(seatOpt.get().getSeatStatus());
        }
        return false;
    }

    public boolean isSeatLockValid(String seatId) {
        String lockKey = buildLockKey(seatId);
        Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
        return ttl != null && ttl > 0;
    }

    public long getRemainingLockTime(String seatId) {
        String lockKey = buildLockKey(seatId);
        Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
        return ttl != null ? ttl : 0;
    }

    public void releaseExpiredLocks() {
        log.info("Releasing expired seat locks...");
        int releasedCount = 0;

        java.util.List<Seat> lockedSeats = seatRepository.findAll().stream()
                .filter(seat -> "locked".equals(seat.getSeatStatus()))
                .toList();

        for (Seat seat : lockedSeats) {
            if (!isSeatLockValid(seat.getSeatId())) {
                releaseSeatLock(seat.getSeatId());
                releasedCount++;
            }
        }

        log.info("Released {} expired seat locks", releasedCount);
    }

    public String getTicketTypeForLockedSeat(String seatId) {
        String lockKey = buildLockKey(seatId);
        return redisTemplate.opsForValue().get(lockKey);
    }

    private String buildLockKey(String seatId) {
        return "eventticket:seat:lock:" + seatId;
    }

    public void logLockConfiguration() {
        log.info("Ticket Lock Configuration:");
        lockConfig.getTimeouts().forEach((ticketType, config) -> {
            log.info("  TicketType: {}, LockTimeout: {}s, PaymentTimeout: {}m, AutoRelease: {}m",
                ticketType,
                config.getLockTimeoutSeconds(),
                config.getPaymentTimeoutMinutes(),
                config.getAutoReleaseDelayMinutes()
            );
        });
    }
}
