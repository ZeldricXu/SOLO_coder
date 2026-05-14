package com.movie.service;

import com.movie.dto.ScheduleCreateRequest;
import com.movie.dto.ScheduleQueryResponse;
import com.movie.entity.Cinema;
import com.movie.entity.Movie;
import com.movie.entity.Schedule;
import com.movie.exception.MovieException;
import com.movie.repository.ScheduleRepository;
import com.movie.scheduler.ScheduleAsyncWorker;
import com.movie.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class ScheduleService {

    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_CLOSED = "closed";
    public static final String STATUS_FULL = "full";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_CONFIGURING = "configuring";
    public static final String STATUS_READY = "ready";

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private MovieService movieService;

    @Autowired
    private CinemaService cinemaService;

    @Autowired
    private SeatService seatService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ScheduleAsyncWorker scheduleAsyncWorker;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, String> asyncTaskStatus = new ConcurrentHashMap<>();

    public List<Schedule> getAllSchedules() {
        return scheduleRepository.findAll();
    }

    public Optional<Schedule> getScheduleById(String scheduleId) {
        return scheduleRepository.findByScheduleId(scheduleId);
    }

    public Schedule getScheduleOrThrow(String scheduleId) {
        return scheduleRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new MovieException(404, "排片不存在: " + scheduleId));
    }

    public List<Schedule> getSchedulesByMovie(String movieId) {
        return scheduleRepository.findByMovieId(movieId);
    }

    public List<Schedule> getSchedulesByCinema(String cinemaId) {
        return scheduleRepository.findByCinemaId(cinemaId);
    }

    public List<ScheduleQueryResponse> querySchedules(String movieId, LocalDate date) {
        if (movieId == null || movieId.isEmpty()) {
            throw new MovieException(400, "电影ID不能为空");
        }
        if (!movieService.exists(movieId)) {
            throw new MovieException(404, "电影不存在: " + movieId);
        }
        LocalDate queryDate = date != null ? date : LocalDate.now();
        List<Schedule> schedules = scheduleRepository.findByMovieIdAndScheduleDate(movieId, queryDate);
        return schedules.stream().map(this::toQueryResponse).collect(Collectors.toList());
    }

    private ScheduleQueryResponse toQueryResponse(Schedule schedule) {
        ScheduleQueryResponse response = new ScheduleQueryResponse();
        response.setScheduleId(schedule.getScheduleId());
        response.setMovieId(schedule.getMovieId());
        response.setCinemaId(schedule.getCinemaId());
        
        Cinema cinema = cinemaService.getCinemaById(schedule.getCinemaId()).orElse(null);
        response.setCinema(cinema != null ? cinema.getCinemaName() : schedule.getCinemaId());
        
        response.setDate(schedule.getScheduleDate() != null ? 
                schedule.getScheduleDate().format(DateTimeFormatter.ISO_LOCAL_DATE) : null);
        response.setTime(schedule.getScheduleTime() != null ? 
                schedule.getScheduleTime().format(DateTimeFormatter.ofPattern("HH:mm")) : null);
        response.setPrice(schedule.getSchedulePrice());
        response.setSeats(schedule.getScheduleSeats());
        response.setAvailable(schedule.getScheduleAvailable());
        response.setStatus(schedule.getScheduleStatus());
        return response;
    }

    public int getPendingTaskCount() {
        return scheduleAsyncWorker.getPendingTaskCount();
    }

    public int getProcessingTaskCount() {
        return scheduleAsyncWorker.getProcessingTaskCount();
    }

    public int getCompletedTaskCount() {
        return scheduleAsyncWorker.getCompletedTaskCount();
    }

    public int getFailedTaskCount() {
        return scheduleAsyncWorker.getFailedTaskCount();
    }

    public ScheduleAsyncWorker.TaskStatus getScheduleTaskStatus(String taskId) {
        return scheduleAsyncWorker.getTaskStatus(taskId);
    }

    public String createScheduleAsync(ScheduleCreateRequest request) {
        return createScheduleAsync(request, null, null);
    }

    public String createScheduleAsync(ScheduleCreateRequest request, Consumer<Schedule> onComplete, Consumer<Exception> onError) {
        if (request.getMovieId() == null || request.getCinemaId() == null) {
            throw new MovieException(400, "电影ID和影院ID不能为空");
        }

        String taskId = "async_" + IdGenerator.generateUUID();
        
        scheduleAsyncWorker.submitTask(taskId, request, onComplete, onError);

        return taskId;
    }

    public String getAsyncTaskStatus(String taskId) {
        return scheduleAsyncWorker.getTaskStatusString(taskId);
    }

    public void clearCompletedTasks() {
        scheduleAsyncWorker.clearCompletedTasks();
    }

    @Transactional
    public Schedule createSchedule(ScheduleCreateRequest request) {
        if (request.getMovieId() == null || request.getCinemaId() == null) {
            throw new MovieException(400, "电影ID和影院ID不能为空");
        }

        Movie movie = movieService.getMovieOrThrow(request.getMovieId());
        Cinema cinema = cinemaService.getCinemaOrThrow(request.getCinemaId());

        Schedule schedule = new Schedule();
        schedule.setScheduleId(IdGenerator.generateScheduleId());
        schedule.setMovieId(request.getMovieId());
        schedule.setCinemaId(request.getCinemaId());
        schedule.setScheduleDate(request.getScheduleDate() != null ? request.getScheduleDate() : LocalDate.now());
        schedule.setScheduleTime(request.getScheduleTime());
        schedule.setSchedulePrice(request.getSchedulePrice());
        
        int seatTotal = request.getScheduleSeats() != null ? request.getScheduleSeats() : 
                (cinema.getSeatTotal() != null ? cinema.getSeatTotal() : 100);
        
        int rowCount = (int) Math.sqrt(seatTotal);
        int colCount = seatTotal / rowCount;
        if (rowCount * colCount < seatTotal) {
            colCount++;
        }
        
        schedule.setScheduleSeats(seatTotal);
        schedule.setScheduleAvailable(seatTotal);
        schedule.setScheduleStatus(STATUS_AVAILABLE);
        schedule.setCreatedAt(LocalDateTime.now());

        Schedule savedSchedule = scheduleRepository.save(schedule);

        seatService.initializeSeats(savedSchedule.getScheduleId(), rowCount, colCount, 
                request.getSchedulePrice());

        movieService.incrementScheduleCount(request.getMovieId());
        cinemaService.incrementScheduleCount(request.getCinemaId());
        analysisService.incrementScheduleCount();
        historyService.recordScheduleCreation(savedSchedule, movie, cinema);

        return savedSchedule;
    }

    @Transactional
    public void decreaseAvailableSeats(String scheduleId, int count) {
        Schedule schedule = getScheduleOrThrow(scheduleId);
        int available = schedule.getScheduleAvailable() != null ? schedule.getScheduleAvailable() : 0;
        if (available < count) {
            throw new MovieException(400, "可用座位不足");
        }
        schedule.setScheduleAvailable(available - count);
        if (schedule.getScheduleAvailable() <= 0) {
            schedule.setScheduleStatus(STATUS_FULL);
        }
        scheduleRepository.save(schedule);
    }

    @Transactional
    public void increaseAvailableSeats(String scheduleId, int count) {
        Schedule schedule = getScheduleOrThrow(scheduleId);
        int available = schedule.getScheduleAvailable() != null ? schedule.getScheduleAvailable() : 0;
        int total = schedule.getScheduleSeats() != null ? schedule.getScheduleSeats() : available + count;
        schedule.setScheduleAvailable(Math.min(available + count, total));
        if (STATUS_FULL.equals(schedule.getScheduleStatus()) && schedule.getScheduleAvailable() > 0) {
            schedule.setScheduleStatus(STATUS_AVAILABLE);
        }
        scheduleRepository.save(schedule);
    }

    @Transactional
    public Schedule updateSchedule(String scheduleId, ScheduleCreateRequest request) {
        Schedule schedule = getScheduleOrThrow(scheduleId);
        if (request.getScheduleDate() != null) {
            schedule.setScheduleDate(request.getScheduleDate());
        }
        if (request.getScheduleTime() != null) {
            schedule.setScheduleTime(request.getScheduleTime());
        }
        if (request.getSchedulePrice() != null) {
            schedule.setSchedulePrice(request.getSchedulePrice());
        }
        return scheduleRepository.save(schedule);
    }

    @Transactional
    public void closeSchedule(String scheduleId) {
        Schedule schedule = getScheduleOrThrow(scheduleId);
        schedule.setScheduleStatus(STATUS_CLOSED);
        scheduleRepository.save(schedule);
    }

    @Transactional
    public void deleteSchedule(String scheduleId) {
        Schedule schedule = getScheduleOrThrow(scheduleId);
        scheduleRepository.delete(schedule);
    }

    public void validateSchedule(Schedule schedule) {
        if (STATUS_CLOSED.equals(schedule.getScheduleStatus())) {
            throw new MovieException(400, "场次已关闭");
        }
        if (STATUS_FULL.equals(schedule.getScheduleStatus())) {
            throw new MovieException(400, "场次已满");
        }
        if (STATUS_PENDING.equals(schedule.getScheduleStatus())) {
            throw new MovieException(400, "场次配置中");
        }
        if (STATUS_CONFIGURING.equals(schedule.getScheduleStatus())) {
            throw new MovieException(400, "场次配置中");
        }
        int available = schedule.getScheduleAvailable() != null ? schedule.getScheduleAvailable() : 0;
        if (available <= 0) {
            throw new MovieException(400, "场次已满");
        }
    }

    public boolean exists(String scheduleId) {
        return scheduleRepository.existsByScheduleId(scheduleId);
    }

    public void shutdown() {
        asyncExecutor.shutdown();
    }
}
