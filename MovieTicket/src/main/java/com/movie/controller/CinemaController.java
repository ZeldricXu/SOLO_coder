package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.dto.CinemaCreateRequest;
import com.movie.entity.Cinema;
import com.movie.service.CinemaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/cinemas")
public class CinemaController {

    @Autowired
    private CinemaService cinemaService;

    @GetMapping
    public ApiResponse<List<Cinema>> list() {
        return ApiResponse.success(cinemaService.getAllCinemas());
    }

    @GetMapping("/{cinemaId}")
    public ApiResponse<Cinema> get(@PathVariable String cinemaId) {
        return ApiResponse.success(cinemaService.getCinemaOrThrow(cinemaId));
    }

    @PostMapping
    public ApiResponse<Cinema> create(@RequestBody CinemaCreateRequest request) {
        return ApiResponse.success(cinemaService.createCinema(request));
    }

    @PutMapping("/{cinemaId}")
    public ApiResponse<Cinema> update(@PathVariable String cinemaId, @RequestBody CinemaCreateRequest request) {
        return ApiResponse.success(cinemaService.updateCinema(cinemaId, request));
    }

    @DeleteMapping("/{cinemaId}")
    public ApiResponse<Void> delete(@PathVariable String cinemaId) {
        cinemaService.deleteCinema(cinemaId);
        return ApiResponse.success(null);
    }
}
