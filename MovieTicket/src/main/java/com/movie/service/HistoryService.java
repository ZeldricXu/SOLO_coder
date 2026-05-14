package com.movie.service;

import com.movie.entity.*;
import com.movie.repository.TicketHistoryRepository;
import com.movie.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private TicketHistoryRepository historyRepository;

    @Transactional
    public void recordScheduleCreation(Schedule schedule, Movie movie, Cinema cinema) {
        TicketHistory history = new TicketHistory();
        history.setScheduleId(schedule.getScheduleId());
        history.setMovieId(schedule.getMovieId());
        history.setCinemaId(schedule.getCinemaId());
        history.setMovieName(movie != null ? movie.getMovieName() : null);
        history.setCinemaName(cinema != null ? cinema.getCinemaName() : null);
        history.setAction("SCHEDULE_CREATED");
        history.setCreatedAt(LocalDateTime.now());
        history.setRemark("排片已创建: 日期=" + schedule.getScheduleDate() + 
                ", 时间=" + schedule.getScheduleTime() + 
                ", 价格=" + schedule.getSchedulePrice());
        historyRepository.save(history);
    }

    @Transactional
    public void recordTicketStatusChange(Ticket ticket, Movie movie, Cinema cinema,
                                          String oldStatus, String newStatus, 
                                          String action, String remark) {
        TicketHistory history = new TicketHistory();
        history.setTicketId(ticket.getTicketId());
        history.setUserId(ticket.getUserId());
        history.setScheduleId(ticket.getScheduleId());
        history.setMovieId(movie != null ? movie.getMovieId() : null);
        history.setCinemaId(cinema != null ? cinema.getCinemaId() : null);
        history.setMovieName(movie != null ? movie.getMovieName() : ticket.getMovieName());
        history.setCinemaName(cinema != null ? cinema.getCinemaName() : ticket.getCinemaName());
        history.setAction(action);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        history.setTicketAmount(ticket.getTicketAmount());
        history.setSeatIdsJson(ticket.getSeatIdsJson());
        history.setCreatedAt(LocalDateTime.now());
        history.setRemark(remark);
        historyRepository.save(history);
    }

    @Transactional
    public void recordTicketCreation(Ticket ticket, Movie movie, Cinema cinema) {
        TicketHistory history = new TicketHistory();
        history.setTicketId(ticket.getTicketId());
        history.setUserId(ticket.getUserId());
        history.setScheduleId(ticket.getScheduleId());
        history.setMovieId(movie != null ? movie.getMovieId() : null);
        history.setCinemaId(cinema != null ? cinema.getCinemaId() : null);
        history.setMovieName(movie != null ? movie.getMovieName() : ticket.getMovieName());
        history.setCinemaName(cinema != null ? cinema.getCinemaName() : ticket.getCinemaName());
        history.setAction("CREATED");
        history.setNewStatus(ticket.getTicketStatus());
        history.setTicketAmount(ticket.getTicketAmount());
        history.setSeatIdsJson(ticket.getSeatIdsJson());
        history.setCreatedAt(LocalDateTime.now());
        history.setRemark("票务已创建");
        historyRepository.save(history);
    }

    public List<TicketHistory> getHistoryByTicketId(String ticketId) {
        return historyRepository.findByTicketId(ticketId);
    }

    public List<TicketHistory> getHistoryByUserId(String userId) {
        return historyRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<TicketHistory> getHistoryByUserIdAndTimeRange(String userId, 
                                                               LocalDateTime startTime, 
                                                               LocalDateTime endTime) {
        return historyRepository.findByUserIdAndCreatedAtBetween(userId, startTime, endTime);
    }

    public List<TicketHistory> getHistoryByMovieId(String movieId) {
        return historyRepository.findByMovieId(movieId);
    }

    public List<TicketHistory> getHistoryByCinemaId(String cinemaId) {
        return historyRepository.findByCinemaId(cinemaId);
    }
}
