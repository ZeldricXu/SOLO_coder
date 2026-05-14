package com.movie.service;

import com.movie.config.MovieTypeConfig;
import com.movie.dto.MovieCreateRequest;
import com.movie.entity.Movie;
import com.movie.exception.MovieException;
import com.movie.repository.MovieRepository;
import com.movie.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MovieService {

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private MovieTypeConfig movieTypeConfig;

    public List<Movie> getAllMovies() {
        return movieRepository.findAll();
    }

    public Optional<Movie> getMovieById(String movieId) {
        return movieRepository.findByMovieId(movieId);
    }

    public Movie getMovieOrThrow(String movieId) {
        return movieRepository.findByMovieId(movieId)
                .orElseThrow(() -> new MovieException(404, "电影不存在: " + movieId));
    }

    public List<Movie> getMoviesByStatus(String status) {
        return movieRepository.findByMovieStatus(status);
    }

    public List<Movie> getMoviesByType(String typeCode) {
        return movieRepository.findByMovieType(typeCode);
    }

    public List<Map<String, Object>> getMovieTypes() {
        List<MovieTypeConfig.MovieType> types = movieTypeConfig.getTypes();
        return types.stream()
                .filter(t -> t.getEnabled() != null && t.getEnabled())
                .sorted((a, b) -> {
                    Integer sortA = a.getSortOrder() != null ? a.getSortOrder() : 999;
                    Integer sortB = b.getSortOrder() != null ? b.getSortOrder() : 999;
                    return sortA.compareTo(sortB);
                })
                .map(this::typeToMap)
                .collect(java.util.stream.Collectors.toList());
    }

    private Map<String, Object> typeToMap(MovieTypeConfig.MovieType type) {
        Map<String, Object> map = new HashMap<>();
        map.put("code", type.getCode());
        map.put("name", type.getName());
        map.put("description", type.getDescription());
        map.put("color", type.getColor());
        map.put("sortOrder", type.getSortOrder());
        return map;
    }

    public boolean isValidMovieType(String typeCode) {
        return movieTypeConfig.isValidType(typeCode);
    }

    public String getMovieTypeName(String typeCode) {
        return movieTypeConfig.getTypeName(typeCode);
    }

    @Transactional
    public Movie createMovie(MovieCreateRequest request) {
        validateMovieRequest(request);

        Movie movie = new Movie();
        movie.setMovieId(IdGenerator.generateMovieId());
        movie.setMovieName(request.getMovieName());
        movie.setMovieType(normalizeMovieType(request.getMovieType()));
        movie.setMovieDuration(request.getMovieDuration());
        movie.setMovieRating(request.getMovieRating() != null ? request.getMovieRating() : 0.0);
        movie.setMovieStatus(request.getMovieStatus() != null ? request.getMovieStatus() : "showing");
        movie.setMoviePoster(request.getMoviePoster());
        movie.setReleaseDate(request.getReleaseDate());
        movie.setCreatedAt(LocalDateTime.now());
        movie.setScheduleCount(0);
        return movieRepository.save(movie);
    }

    @Transactional
    public Movie updateMovie(String movieId, MovieCreateRequest request) {
        Movie movie = getMovieOrThrow(movieId);
        if (request.getMovieName() != null) {
            movie.setMovieName(request.getMovieName());
        }
        if (request.getMovieType() != null) {
            movie.setMovieType(normalizeMovieType(request.getMovieType()));
        }
        if (request.getMovieDuration() != null) {
            if (request.getMovieDuration() <= 0) {
                throw new MovieException(400, "电影时长必须为正整数");
            }
            movie.setMovieDuration(request.getMovieDuration());
        }
        if (request.getMovieRating() != null) {
            movie.setMovieRating(request.getMovieRating());
        }
        if (request.getMovieStatus() != null) {
            movie.setMovieStatus(request.getMovieStatus());
        }
        if (request.getMoviePoster() != null) {
            movie.setMoviePoster(request.getMoviePoster());
        }
        if (request.getReleaseDate() != null) {
            movie.setReleaseDate(request.getReleaseDate());
        }
        return movieRepository.save(movie);
    }

    @Transactional
    public void incrementScheduleCount(String movieId) {
        Movie movie = getMovieOrThrow(movieId);
        movie.setScheduleCount(movie.getScheduleCount() + 1);
        movieRepository.save(movie);
    }

    @Transactional
    public void decrementScheduleCount(String movieId) {
        Movie movie = getMovieOrThrow(movieId);
        if (movie.getScheduleCount() > 0) {
            movie.setScheduleCount(movie.getScheduleCount() - 1);
            movieRepository.save(movie);
        }
    }

    @Transactional
    public void deleteMovie(String movieId) {
        Movie movie = getMovieOrThrow(movieId);
        movieRepository.delete(movie);
    }

    public boolean exists(String movieId) {
        return movieRepository.existsByMovieId(movieId);
    }

    private void validateMovieRequest(MovieCreateRequest request) {
        if (request.getMovieName() == null || request.getMovieName().trim().isEmpty()) {
            throw new MovieException(400, "电影名称不能为空");
        }
        if (request.getMovieType() == null || request.getMovieType().trim().isEmpty()) {
            throw new MovieException(400, "电影类型不能为空");
        }
        if (request.getMovieDuration() != null && request.getMovieDuration() <= 0) {
            throw new MovieException(400, "电影时长必须为正整数");
        }
        if (!isValidMovieType(request.getMovieType())) {
            throw new MovieException(400, "无效的电影类型: " + request.getMovieType());
        }
    }

    private String normalizeMovieType(String typeCode) {
        if (typeCode == null) {
            return movieTypeConfig.getDefaultType();
        }
        String lowerType = typeCode.toLowerCase();
        if (isValidMovieType(lowerType)) {
            return lowerType;
        }
        throw new MovieException(400, "无效的电影类型: " + typeCode);
    }
}
