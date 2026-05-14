package com.travelbooking.service;

import com.travelbooking.dto.ItineraryQueryResponse;
import com.travelbooking.dto.ItineraryQueryWrapper;
import com.travelbooking.exception.BusinessException;
import com.travelbooking.model.*;
import com.travelbooking.repository.ItineraryRepository;
import com.travelbooking.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ItineraryService {

    private final ItineraryRepository itineraryRepository;
    private final GuideService guideService;
    private final AnalyticsService analyticsService;
    private final HistoryService historyService;

    @Transactional
    public Itinerary createItinerary(Booking booking, Route route, Team team) {
        Itinerary itinerary = new Itinerary();
        itinerary.setItineraryId(IdGenerator.generateItineraryId());
        itinerary.setBookingId(booking.getBookingId());
        itinerary.setRouteId(route.getRouteId());
        itinerary.setTeamId(team.getTeamId());
        itinerary.setItineraryStatus("pending_departure");

        if (route.getRouteDuration() != null) {
            LocalDate start = LocalDate.now().plusDays(7);
            itinerary.setItineraryStart(start);
            itinerary.setItineraryEnd(start.plusDays(route.getRouteDuration()));
        }

        return itineraryRepository.save(itinerary);
    }

    public ItineraryQueryWrapper queryItinerary(String bookingId) {
        List<Itinerary> itineraries = itineraryRepository.findByBookingId(bookingId);
        if (itineraries.isEmpty()) {
            throw new BusinessException(404, "行程不存在");
        }

        Itinerary itinerary = itineraries.get(0);

        String guideName = null;
        if (itinerary.getGuideId() != null) {
            guideName = guideService.getGuideById(itinerary.getGuideId())
                    .map(Guide::getGuideName)
                    .orElse(null);
        }

        ItineraryQueryResponse response = ItineraryQueryResponse.builder()
                .status(itinerary.getItineraryStatus())
                .start(itinerary.getItineraryStart())
                .end(itinerary.getItineraryEnd())
                .guideName(guideName)
                .build();

        return ItineraryQueryWrapper.builder().itinerary(response).build();
    }

    @Transactional
    public Itinerary departItinerary(String itineraryId) {
        Itinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new BusinessException(404, "行程不存在"));

        if ("completed".equals(itinerary.getItineraryStatus())) {
            throw new BusinessException(400, "行程已完成");
        }

        Guide guide = guideService.assignGuide();
        if (guide == null) {
            throw new BusinessException(400, "导游不足");
        }

        itinerary.setGuideId(guide.getGuideId());
        itinerary.setItineraryStatus("departed");
        itinerary.setItineraryStart(LocalDate.now());

        if (itinerary.getItineraryStart() != null && itinerary.getItineraryEnd() == null) {
            Route mockRoute = new Route();
            mockRoute.setRouteDuration(5);
            itinerary.setItineraryEnd(LocalDate.now().plusDays(mockRoute.getRouteDuration()));
        }

        Itinerary saved = itineraryRepository.save(itinerary);

        guideService.incrementGuideCount(guide.getGuideId());
        analyticsService.updateDepartedStatistics();

        historyService.recordHistory("itinerary", itineraryId,
                "depart", "行程出发，分配导游: " + guide.getGuideName());

        return saved;
    }

    @Transactional
    public Itinerary completeItinerary(String itineraryId) {
        Itinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new BusinessException(404, "行程不存在"));

        if ("completed".equals(itinerary.getItineraryStatus())) {
            throw new BusinessException(400, "行程已完成");
        }

        itinerary.setItineraryStatus("completed");
        itinerary.setItineraryEnd(LocalDate.now());

        Itinerary saved = itineraryRepository.save(itinerary);

        if (itinerary.getGuideId() != null) {
            guideService.incrementCompletedCount(itinerary.getGuideId());
        }
        analyticsService.updateCompletedStatistics();

        historyService.recordHistory("itinerary", itineraryId,
                "complete", "行程完成");

        return saved;
    }

    public List<Itinerary> getAllItineraries() {
        return itineraryRepository.findAll();
    }

    public Optional<Itinerary> getItineraryById(String itineraryId) {
        return itineraryRepository.findById(itineraryId);
    }

    public List<Itinerary> getItinerariesByRouteId(String routeId) {
        return itineraryRepository.findByRouteId(routeId);
    }

    public List<Itinerary> getItinerariesByGuideId(String guideId) {
        return itineraryRepository.findByGuideId(guideId);
    }

    @Transactional
    public Itinerary updateItinerary(String itineraryId, Itinerary itinerary) {
        Itinerary existing = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new BusinessException(404, "行程不存在"));

        if (itinerary.getItineraryStatus() != null) {
            existing.setItineraryStatus(itinerary.getItineraryStatus());
        }
        if (itinerary.getItineraryStart() != null) {
            existing.setItineraryStart(itinerary.getItineraryStart());
        }
        if (itinerary.getItineraryEnd() != null) {
            existing.setItineraryEnd(itinerary.getItineraryEnd());
        }
        if (itinerary.getGuideId() != null) {
            existing.setGuideId(itinerary.getGuideId());
        }

        return itineraryRepository.save(existing);
    }

    public void deleteItinerary(String itineraryId) {
        itineraryRepository.deleteById(itineraryId);
    }
}
