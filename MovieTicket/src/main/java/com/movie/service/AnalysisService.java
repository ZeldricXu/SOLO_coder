package com.movie.service;

import com.movie.entity.BoxOfficeStat;
import com.movie.entity.Cinema;
import com.movie.entity.Movie;
import com.movie.entity.Ticket;
import com.movie.repository.BoxOfficeStatRepository;
import com.movie.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AnalysisService {

    @Autowired
    private BoxOfficeStatRepository statRepository;

    private int scheduleCount = 0;
    private int ticketCount = 0;
    private BigDecimal totalBoxOffice = BigDecimal.ZERO;

    @Transactional
    public void incrementScheduleCount() {
        scheduleCount++;
    }

    @Transactional
    public void recordTicketSale(Ticket ticket, Movie movie, Cinema cinema) {
        if (ticket == null || ticket.getTicketAmount() == null) {
            return;
        }

        LocalDate today = LocalDate.now();
        String movieId = movie != null ? movie.getMovieId() : (ticket.getScheduleId() != null ? ticket.getScheduleId() : "unknown");
        String cinemaId = cinema != null ? cinema.getCinemaId() : (ticket.getScheduleId() != null ? ticket.getScheduleId() : "unknown");

        Optional<BoxOfficeStat> existingOpt = statRepository.findByStatDateAndMovieIdAndCinemaId(
                today, movieId, cinemaId);

        BoxOfficeStat stat;
        if (existingOpt.isPresent()) {
            stat = existingOpt.get();
            stat.setTicketCount((stat.getTicketCount() != null ? stat.getTicketCount() : 0) + 1);
            stat.setBoxOffice((stat.getBoxOffice() != null ? stat.getBoxOffice() : BigDecimal.ZERO)
                    .add(ticket.getTicketAmount()));
        } else {
            stat = new BoxOfficeStat();
            stat.setStatId(IdGenerator.generateStatId());
            stat.setStatDate(today);
            stat.setMovieId(movieId);
            stat.setCinemaId(cinemaId);
            stat.setTicketCount(1);
            stat.setBoxOffice(ticket.getTicketAmount());
        }
        statRepository.save(stat);

        ticketCount++;
        totalBoxOffice = totalBoxOffice.add(ticket.getTicketAmount());
    }

    @Transactional
    public void recordTicketRefund(Ticket ticket, Movie movie, Cinema cinema) {
        if (ticket == null || ticket.getTicketAmount() == null) {
            return;
        }

        LocalDate today = LocalDate.now();
        String movieId = movie != null ? movie.getMovieId() : "unknown";
        String cinemaId = cinema != null ? cinema.getCinemaId() : "unknown";

        Optional<BoxOfficeStat> existingOpt = statRepository.findByStatDateAndMovieIdAndCinemaId(
                today, movieId, cinemaId);

        if (existingOpt.isPresent()) {
            BoxOfficeStat stat = existingOpt.get();
            stat.setTicketCount(Math.max(0, (stat.getTicketCount() != null ? stat.getTicketCount() : 0) - 1));
            stat.setBoxOffice((stat.getBoxOffice() != null ? stat.getBoxOffice() : BigDecimal.ZERO)
                    .subtract(ticket.getTicketAmount()));
            if (stat.getBoxOffice().compareTo(BigDecimal.ZERO) < 0) {
                stat.setBoxOffice(BigDecimal.ZERO);
            }
            statRepository.save(stat);
        }

        ticketCount = Math.max(0, ticketCount - 1);
        totalBoxOffice = totalBoxOffice.subtract(ticket.getTicketAmount());
        if (totalBoxOffice.compareTo(BigDecimal.ZERO) < 0) {
            totalBoxOffice = BigDecimal.ZERO;
        }
    }

    public List<BoxOfficeStat> getStatsByDate(LocalDate date) {
        return statRepository.findByStatDate(date != null ? date : LocalDate.now());
    }

    public List<BoxOfficeStat> getStatsByMovie(String movieId) {
        return statRepository.findByMovieId(movieId);
    }

    public List<BoxOfficeStat> getStatsByCinema(String cinemaId) {
        return statRepository.findByCinemaId(cinemaId);
    }

    public Map<String, Object> getOverallStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSchedules", scheduleCount);
        stats.put("totalTickets", ticketCount);
        stats.put("totalBoxOffice", totalBoxOffice);
        return stats;
    }

    public Map<String, Object> getMovieStats(String movieId) {
        Map<String, Object> stats = new HashMap<>();
        Long ticketCountSum = statRepository.sumTicketCountByMovieId(movieId);
        BigDecimal boxOfficeSum = statRepository.sumBoxOfficeByMovieId(movieId);
        stats.put("movieId", movieId);
        stats.put("totalTickets", ticketCountSum != null ? ticketCountSum : 0);
        stats.put("totalBoxOffice", boxOfficeSum != null ? boxOfficeSum : BigDecimal.ZERO);
        return stats;
    }
}
