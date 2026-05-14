package com.maplocation.controller;

import com.maplocation.dto.ApiResponse;
import com.maplocation.dto.SearchRequest;
import com.maplocation.dto.SearchResponse;
import com.maplocation.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @PostMapping("/locations/search")
    public ApiResponse<SearchResponse> searchLocations(@RequestBody SearchRequest request) {
        SearchResponse response = searchService.searchLocations(request);
        return ApiResponse.success(response);
    }
}
