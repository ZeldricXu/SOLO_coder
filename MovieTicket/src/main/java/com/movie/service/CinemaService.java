package com.movie.service;

import com.movie.dto.CinemaCreateRequest;
import com.movie.entity.Cinema;
import com.movie.exception.MovieException;
import com.movie.repository.CinemaRepository;
import com.movie.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class CinemaService {

    @Autowired
    private CinemaRepository cinemaRepository;

    public List<Cinema> getAllCinemas() {
        return cinemaRepository.findAll();
    }

    public Optional<Cinema> getCinemaById(String cinemaId) {
        return cinemaRepository.findByCinemaId(cinemaId);
    }

    public Cinema getCinemaOrThrow(String cinemaId) {
        return cinemaRepository.findByCinemaId(cinemaId)
                .orElseThrow(() -> new MovieException(404, "影院不存在: " + cinemaId));
    }

    public List<Cinema> getCinemasByStatus(String status) {
        return cinemaRepository.findByCinemaStatus(status);
    }

    public List<Cinema> getCinemasByRegion(String region) {
        return cinemaRepository.findByCinemaRegion(region);
    }

    @Transactional
    public Cinema createCinema(CinemaCreateRequest request) {
        Cinema cinema = new Cinema();
        cinema.setCinemaId(IdGenerator.generateCinemaId());
        cinema.setCinemaName(request.getCinemaName());
        cinema.setCinemaAddress(request.getCinemaAddress());
        cinema.setCinemaRegion(request.getCinemaRegion());
        cinema.setCinemaStatus(request.getCinemaStatus() != null ? request.getCinemaStatus() : "active");
        cinema.setCinemaRating(request.getCinemaRating() != null ? request.getCinemaRating() : 4.0);
        cinema.setSeatTotal(request.getSeatTotal() != null ? request.getSeatTotal() : 100);
        cinema.setScheduleCount(0);
        cinema.setCreatedAt(LocalDateTime.now());
        return cinemaRepository.save(cinema);
    }

    @Transactional
    public Cinema updateCinema(String cinemaId, CinemaCreateRequest request) {
        Cinema cinema = getCinemaOrThrow(cinemaId);
        if (request.getCinemaName() != null) {
            cinema.setCinemaName(request.getCinemaName());
        }
        if (request.getCinemaAddress() != null) {
            cinema.setCinemaAddress(request.getCinemaAddress());
        }
        if (request.getCinemaRegion() != null) {
            cinema.setCinemaRegion(request.getCinemaRegion());
        }
        if (request.getCinemaStatus() != null) {
            cinema.setCinemaStatus(request.getCinemaStatus());
        }
        if (request.getCinemaRating() != null) {
            cinema.setCinemaRating(request.getCinemaRating());
        }
        if (request.getSeatTotal() != null) {
            cinema.setSeatTotal(request.getSeatTotal());
        }
        return cinemaRepository.save(cinema);
    }

    @Transactional
    public void incrementScheduleCount(String cinemaId) {
        Cinema cinema = getCinemaOrThrow(cinemaId);
        cinema.setScheduleCount(cinema.getScheduleCount() + 1);
        cinemaRepository.save(cinema);
    }

    @Transactional
    public void deleteCinema(String cinemaId) {
        Cinema cinema = getCinemaOrThrow(cinemaId);
        cinemaRepository.delete(cinema);
    }

    public boolean exists(String cinemaId) {
        return cinemaRepository.existsByCinemaId(cinemaId);
    }
}
