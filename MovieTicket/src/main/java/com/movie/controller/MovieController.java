package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.dto.MovieCreateRequest;
import com.movie.entity.Movie;
import com.movie.service.MovieService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/movies")
public class MovieController {

    @Autowired
    private MovieService movieService;

    @GetMapping
    public ApiResponse<List<Movie>> list() {
        return ApiResponse.success(movieService.getAllMovies());
    }

    @GetMapping("/{movieId}")
    public ApiResponse<Movie> get(@PathVariable String movieId) {
        return ApiResponse.success(movieService.getMovieOrThrow(movieId));
    }

    @GetMapping("/types")
    public ApiResponse<List<Map<String, Object>>> getTypes() {
        return ApiResponse.success(movieService.getMovieTypes());
    }

    @GetMapping("/type/{typeCode}")
    public ApiResponse<List<Movie>> getByType(@PathVariable String typeCode) {
        return ApiResponse.success(movieService.getMoviesByType(typeCode));
    }

    @PostMapping
    public ApiResponse<Movie> create(@RequestBody MovieCreateRequest request) {
        return ApiResponse.success(movieService.createMovie(request));
    }

    @PutMapping("/{movieId}")
    public ApiResponse<Movie> update(@PathVariable String movieId, @RequestBody MovieCreateRequest request) {
        return ApiResponse.success(movieService.updateMovie(movieId, request));
    }

    @DeleteMapping("/{movieId}")
    public ApiResponse<Void> delete(@PathVariable String movieId) {
        movieService.deleteMovie(movieId);
        return ApiResponse.success(null);
    }
}
