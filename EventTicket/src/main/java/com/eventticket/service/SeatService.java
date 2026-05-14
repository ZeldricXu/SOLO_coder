package com.eventticket.service;

import com.eventticket.config.SeatSectionConfig;
import com.eventticket.config.SeatSectionConfig.SectionConfig;
import com.eventticket.config.TicketLockConfig;
import com.eventticket.dto.SeatAssignRequest;
import com.eventticket.entity.Event;
import com.eventticket.entity.Seat;
import com.eventticket.repository.EventRepository;
import com.eventticket.repository.SeatRepository;
import com.eventticket.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SeatService {

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private TicketLockConfig lockConfig;

    @Autowired
    private SeatSectionConfig sectionConfig;

    @Autowired
    private SeatLockService seatLockService;

    @Autowired
    private SeatSectionService seatSectionService;

    @Transactional
    public Seat createSeat(Seat seat) {
        seat.setSeatId(IdGenerator.generateSeatId());
        if (seat.getCreatedAt() == null) {
            seat.setCreatedAt(LocalDateTime.now());
        }

        String section = seat.getSeatSection();
        if (section != null && !sectionConfig.isValidSection(section)) {
            log.warn("Invalid section code: {}, using default", section);
            seat.setSeatSection(sectionConfig.getDefaultSection());
        }

        if (seat.getSeatPrice() <= 0) {
            seat.setSeatPrice(sectionConfig.getBasePrice(seat.getSeatSection()));
        }

        return seatRepository.save(seat);
    }

    @Transactional(readOnly = true)
    public Optional<Seat> getSeatById(String seatId) {
        return seatRepository.findById(seatId);
    }

    @Transactional(readOnly = true)
    public List<Seat> getSeatsByEventId(String eventId) {
        return seatRepository.findByEventId(eventId);
    }

    @Transactional(readOnly = true)
    public List<Seat> getAvailableSeatsByEventId(String eventId) {
        return seatRepository.findAvailableSeatsByEventId(eventId);
    }

    @Transactional(readOnly = true)
    public List<Seat> getAvailableSeatsByEventIdAndSection(String eventId, String section) {
        if (section != null && !section.isEmpty()) {
            if (!sectionConfig.isValidSection(section)) {
                log.warn("Invalid section code: {}", section);
                return seatRepository.findAvailableSeatsByEventId(eventId);
            }
            return seatRepository.findAvailableSeatsByEventIdAndSection(eventId, section);
        }
        return seatRepository.findAvailableSeatsByEventId(eventId);
    }

    @Transactional
    public Seat assignSeat(SeatAssignRequest request) {
        Event event = eventRepository.findById(request.getEventId()).orElse(null);
        if (event == null) {
            throw new RuntimeException("活动不存在");
        }

        String ticketType = request.getTicketType() != null ? request.getTicketType() : lockConfig.getDefaultTicketType();
        String section = request.getSection();

        if (section != null && !section.isEmpty() && !sectionConfig.isValidSection(section)) {
            log.warn("Invalid section code: {}, using default", section);
            section = sectionConfig.getDefaultSection();
        }

        if (request.getSeatId() != null && !request.getSeatId().isEmpty()) {
            return seatLockService.lockSeat(request.getSeatId(), ticketType);
        } else {
            return seatLockService.lockSeatAutoAssign(request.getEventId(), ticketType, section);
        }
    }

    @Transactional
    public Seat updateSeatStatus(String seatId, String status) {
        return seatRepository.findByIdWithLock(seatId).map(seat -> {
            seat.setSeatStatus(status);
            if ("sold".equals(status)) {
                seat.setSoldAt(LocalDateTime.now());
            } else if ("admitted".equals(status)) {
                seat.setAdmittedAt(LocalDateTime.now());
            } else if ("available".equals(status)) {
                seat.setLockedAt(null);
                seat.setSoldAt(null);
                seat.setAdmittedAt(null);
            }
            return seatRepository.save(seat);
        }).orElse(null);
    }

    @Transactional
    public void releaseSeat(String seatId) {
        seatLockService.releaseSeatLock(seatId);
    }

    @Transactional(readOnly = true)
    public long countAvailableSeats(String eventId) {
        return seatRepository.countAvailableSeats(eventId);
    }

    @Transactional(readOnly = true)
    public long countSeatsByStatus(String eventId, String status) {
        return seatRepository.countByEventIdAndSeatStatus(eventId, status);
    }

    public int getLockTimeoutSeconds(String ticketType) {
        return lockConfig.getLockTimeoutSeconds(ticketType);
    }

    public int getBasePriceForSection(String sectionCode) {
        return sectionConfig.getBasePrice(sectionCode);
    }

    public List<String> getValidSections() {
        return sectionConfig.getSectionCodes();
    }

    public SectionConfig getSectionConfig(String sectionCode) {
        return sectionConfig.getSectionConfig(sectionCode);
    }
}
