package com.library.librarymgmt.service.impl;

import com.library.librarymgmt.entity.BorrowStat;
import com.library.librarymgmt.repository.BorrowRepository;
import com.library.librarymgmt.repository.BorrowStatRepository;
import com.library.librarymgmt.repository.BookRepository;
import com.library.librarymgmt.repository.ReaderRepository;
import com.library.librarymgmt.repository.ReserveRepository;
import com.library.librarymgmt.service.AnalysisService;
import com.library.librarymgmt.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class AnalysisServiceImpl implements AnalysisService {

    private final BorrowStatRepository borrowStatRepository;
    private final BookRepository bookRepository;
    private final ReaderRepository readerRepository;
    private final BorrowRepository borrowRepository;
    private final ReserveRepository reserveRepository;

    public AnalysisServiceImpl(BorrowStatRepository borrowStatRepository,
                               BookRepository bookRepository,
                               ReaderRepository readerRepository,
                               BorrowRepository borrowRepository,
                               ReserveRepository reserveRepository) {
        this.borrowStatRepository = borrowStatRepository;
        this.bookRepository = bookRepository;
        this.readerRepository = readerRepository;
        this.borrowRepository = borrowRepository;
        this.reserveRepository = reserveRepository;
    }

    private String getCurrentMonth() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    @Override
    @Transactional
    public BorrowStat getOrCreateCurrentMonthStat() {
        String month = getCurrentMonth();
        Optional<BorrowStat> statOpt = borrowStatRepository.findByStatMonth(month);
        if (statOpt.isPresent()) {
            return statOpt.get();
        }
        BorrowStat stat = new BorrowStat();
        stat.setStatId(IdGenerator.generateStatId());
        stat.setStatMonth(month);
        return borrowStatRepository.save(stat);
    }

    @Override
    @Transactional
    public void incrementBorrowCount() {
        BorrowStat stat = getOrCreateCurrentMonthStat();
        stat.setBorrowCount(stat.getBorrowCount() + 1);
        borrowStatRepository.save(stat);
    }

    @Override
    @Transactional
    public void incrementReturnCount() {
        BorrowStat stat = getOrCreateCurrentMonthStat();
        stat.setReturnCount(stat.getReturnCount() + 1);
        borrowStatRepository.save(stat);
    }

    @Override
    @Transactional
    public void incrementReserveCount() {
        BorrowStat stat = getOrCreateCurrentMonthStat();
        stat.setReserveCount(stat.getReserveCount() + 1);
        borrowStatRepository.save(stat);
    }

    @Override
    @Transactional
    public void incrementOverdueCount() {
        BorrowStat stat = getOrCreateCurrentMonthStat();
        stat.setOverdueCount(stat.getOverdueCount() + 1);
        borrowStatRepository.save(stat);
    }

    @Override
    public Optional<BorrowStat> getStatByMonth(String month) {
        return borrowStatRepository.findByStatMonth(month);
    }

    @Override
    public List<BorrowStat> getAllStats() {
        return borrowStatRepository.findAll();
    }

    @Override
    public long getTotalBooks() {
        return bookRepository.count();
    }

    @Override
    public long getTotalReaders() {
        return readerRepository.count();
    }

    @Override
    public long getActiveBorrowsCount() {
        return borrowRepository.findByBorrowStatus("borrowed").size();
    }

    @Override
    public long getOverdueBorrowsCount() {
        return borrowRepository.findByBorrowStatusAndBorrowDueBefore(
                "borrowed", java.time.Instant.now()).size();
    }

    @Override
    public long getWaitingReservesCount() {
        return reserveRepository.findByReserveStatus("waiting").size();
    }
}
