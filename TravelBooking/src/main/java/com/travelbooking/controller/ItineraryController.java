package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.dto.ItineraryQueryWrapper;
import com.travelbooking.model.Itinerary;
import com.travelbooking.service.ItineraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/itineraries")
@RequiredArgsConstructor
public class ItineraryController {

    private final ItineraryService itineraryService;

    @GetMapping("/query")
    public ApiResponse<ItineraryQueryWrapper> queryItinerary(@RequestParam String bookingId) {
        ItineraryQueryWrapper response = itineraryService.queryItinerary(bookingId);
        return ApiResponse.success(response);
    }

    @GetMapping
    public ApiResponse<List<Itinerary>> getAllItineraries() {
        return ApiResponse.success(itineraryService.getAllItineraries());
    }

    @GetMapping("/{id}")
    public ApiResponse<Itinerary> getItineraryById(@PathVariable String id) {
        return itineraryService.getItineraryById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "行程不存在"));
    }

    @PostMapping("/{id}/depart")
    public ApiResponse<Itinerary> departItinerary(@PathVariable String id) {
        Itinerary itinerary = itineraryService.departItinerary(id);
        return ApiResponse.success(itinerary);
    }

    @PostMapping("/{id}/complete")
    public ApiResponse<Itinerary> completeItinerary(@PathVariable String id) {
        Itinerary itinerary = itineraryService.completeItinerary(id);
        return ApiResponse.success(itinerary);
    }

    @GetMapping("/route/{routeId}")
    public ApiResponse<List<Itinerary>> getItinerariesByRouteId(@PathVariable String routeId) {
        return ApiResponse.success(itineraryService.getItinerariesByRouteId(routeId));
    }

    @GetMapping("/guide/{guideId}")
    public ApiResponse<List<Itinerary>> getItinerariesByGuideId(@PathVariable String guideId) {
        return ApiResponse.success(itineraryService.getItinerariesByGuideId(guideId));
    }

    @PutMapping("/{id}")
    public ApiResponse<Itinerary> updateItinerary(@PathVariable String id, @RequestBody Itinerary itinerary) {
        Itinerary updated = itineraryService.updateItinerary(id, itinerary);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteItinerary(@PathVariable String id) {
        itineraryService.deleteItinerary(id);
        return ApiResponse.success(null);
    }
}
