package com.library.librarymgmt.service.impl;

import com.library.librarymgmt.dto.ReserveRequest;
import com.library.librarymgmt.dto.ReserveResult;
import com.library.librarymgmt.entity.Book;
import com.library.librarymgmt.entity.Reserve;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.ReserveRepository;
import com.library.librarymgmt.service.*;
import com.library.librarymgmt.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ReserveServiceImpl implements ReserveService {

    private static final Logger logger = LoggerFactory.getLogger(ReserveServiceImpl.class);

    private final ReserveRepository reserveRepository;
    private final BookService bookService;
    private final ReaderService readerService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final RedisNotificationQueueService notificationQueueService;

    public ReserveServiceImpl(ReserveRepository reserveRepository,
                              BookService bookService,
                              ReaderService readerService,
                              AnalysisService analysisService,
                              HistoryService historyService,
                              RedisNotificationQueueService notificationQueueService) {
        this.reserveRepository = reserveRepository;
        this.bookService = bookService;
        this.readerService = readerService;
        this.analysisService = analysisService;
        this.historyService = historyService;
        this.notificationQueueService = notificationQueueService;
    }

    @Override
    @Transactional
    public ReserveResult createReserve(ReserveRequest request) {
        String bookId = request.getBook_id();
        String readerId = request.getReader_id();

        if (!readerService.getReaderById(readerId).isPresent()) {
            throw new LibraryException(404, "读者不存在");
        }

        Book book = bookService.getBookById(bookId)
                .orElseThrow(() -> new LibraryException(404, "图书不存在"));

        if (book.getBookAvailable() > 0) {
            throw new LibraryException(400, "图书库存充足，可直接借阅");
        }

        Reserve reserve = new Reserve();
        reserve.setReserveId(IdGenerator.generateReserveId());
        reserve.setBookId(bookId);
        reserve.setReaderId(readerId);
        reserve.setReserveTime(Instant.now());
        reserve.setReserveStatus("waiting");
        reserve.setNotified(false);

        Reserve savedReserve = reserveRepository.save(reserve);

        analysisService.incrementReserveCount();

        historyService.log("reserve", savedReserve.getReserveId(), bookId, readerId,
                "创建图书预约，预约状态: waiting");

        logger.info("预约创建成功: reserveId={}, bookId={}, readerId={}",
                savedReserve.getReserveId(), bookId, readerId);

        ReserveResult result = new ReserveResult();
        result.setReserve_id(savedReserve.getReserveId());
        result.setStatus(savedReserve.getReserveStatus());

        return result;
    }

    @Override
    public Optional<Reserve> getReserveById(String reserveId) {
        return reserveRepository.findByReserveId(reserveId);
    }

    @Override
    public List<Reserve> getAllReserves() {
        return reserveRepository.findAll();
    }

    @Override
    public List<Reserve> getReservesByBookId(String bookId) {
        return reserveRepository.findByBookId(bookId);
    }

    @Override
    public List<Reserve> getReservesByReaderId(String readerId) {
        return reserveRepository.findByReaderId(readerId);
    }

    @Override
    public List<Reserve> getWaitingReservesByBookId(String bookId) {
        return reserveRepository.findByBookIdAndReserveStatusOrderByReserveTimeAsc(bookId, "waiting");
    }

    @Override
    @Transactional
    public Reserve updateReserveStatus(String reserveId, String status) {
        Reserve reserve = reserveRepository.findByReserveId(reserveId)
                .orElseThrow(() -> new LibraryException(404, "预约记录不存在"));
        reserve.setReserveStatus(status);
        Reserve updatedReserve = reserveRepository.save(reserve);
        logger.info("预约状态更新: reserveId={}, status={}", reserveId, status);
        return updatedReserve;
    }

    @Override
    @Transactional
    public void notifyWaitingReaders(String bookId) {
        List<Reserve> waitingReserves = getWaitingReservesByBookId(bookId);

        for (Reserve reserve : waitingReserves) {
            if (!reserve.getNotified()) {
                reserve.setNotified(true);
                reserve.setReserveStatus("notified");
                reserveRepository.save(reserve);

                logger.info("将预约通知任务入队: reserveId={}, bookId={}, readerId={}",
                        reserve.getReserveId(), bookId, reserve.getReaderId());

                boolean enqueued = notificationQueueService.enqueueNotification(
                        reserve.getReserveId(),
                        bookId,
                        reserve.getReaderId()
                );

                if (enqueued) {
                    historyService.log("reserve_notification_enqueued", reserve.getReserveId(), bookId, reserve.getReaderId(),
                            "预约通知任务已入队，等待Worker处理");
                } else {
                    logger.error("预约通知任务入队失败: reserveId={}", reserve.getReserveId());
                }
            }
        }

        logger.info("图书归还后，已处理 {} 个等待中的预约通知: bookId={}",
                waitingReserves.size(), bookId);
    }
}
