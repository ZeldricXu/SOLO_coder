package com.library.librarymgmt.service.impl;

import com.library.librarymgmt.dto.ReaderRequest;
import com.library.librarymgmt.entity.Reader;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.ReaderRepository;
import com.library.librarymgmt.service.ReaderService;
import com.library.librarymgmt.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ReaderServiceImpl implements ReaderService {

    private final ReaderRepository readerRepository;

    public ReaderServiceImpl(ReaderRepository readerRepository) {
        this.readerRepository = readerRepository;
    }

    @Override
    @Transactional
    public Reader createReader(ReaderRequest request) {
        Reader reader = new Reader();
        reader.setReaderId(IdGenerator.generateReaderId());
        reader.setReaderName(request.getReader_name());
        reader.setReaderPhone(request.getReader_phone());
        reader.setReaderType(request.getReader_type());
        reader.setReaderStatus("active");
        reader.setBorrowLimit(request.getBorrow_limit() != null ? request.getBorrow_limit() : 5);
        reader.setBorrowedCount(0);
        return readerRepository.save(reader);
    }

    @Override
    public Optional<Reader> getReaderById(String readerId) {
        return readerRepository.findByReaderId(readerId);
    }

    @Override
    public List<Reader> getAllReaders() {
        return readerRepository.findAll();
    }

    @Override
    public List<Reader> getReadersByStatus(String status) {
        return readerRepository.findByReaderStatus(status);
    }

    @Override
    public List<Reader> getReadersByType(String type) {
        return readerRepository.findByReaderType(type);
    }

    @Override
    @Transactional
    public Reader updateReader(String readerId, ReaderRequest request) {
        Reader reader = readerRepository.findByReaderId(readerId)
                .orElseThrow(() -> new LibraryException(404, "读者不存在"));
        reader.setReaderName(request.getReader_name());
        reader.setReaderPhone(request.getReader_phone());
        reader.setReaderType(request.getReader_type());
        if (request.getBorrow_limit() != null) {
            reader.setBorrowLimit(request.getBorrow_limit());
        }
        return readerRepository.save(reader);
    }

    @Override
    @Transactional
    public void deleteReader(String readerId) {
        Reader reader = readerRepository.findByReaderId(readerId)
                .orElseThrow(() -> new LibraryException(404, "读者不存在"));
        readerRepository.delete(reader);
    }

    @Override
    @Transactional
    public Reader updateReaderStatus(String readerId, String status) {
        Reader reader = readerRepository.findByReaderId(readerId)
                .orElseThrow(() -> new LibraryException(404, "读者不存在"));
        reader.setReaderStatus(status);
        return readerRepository.save(reader);
    }

    @Override
    @Transactional
    public void increaseBorrowedCount(String readerId) {
        Reader reader = readerRepository.findByReaderId(readerId)
                .orElseThrow(() -> new LibraryException(404, "读者不存在"));
        reader.setBorrowedCount(reader.getBorrowedCount() + 1);
        readerRepository.save(reader);
    }

    @Override
    @Transactional
    public void decreaseBorrowedCount(String readerId) {
        Reader reader = readerRepository.findByReaderId(readerId)
                .orElseThrow(() -> new LibraryException(404, "读者不存在"));
        if (reader.getBorrowedCount() > 0) {
            reader.setBorrowedCount(reader.getBorrowedCount() - 1);
        }
        readerRepository.save(reader);
    }

    @Override
    public boolean canBorrow(String readerId) {
        Optional<Reader> readerOpt = readerRepository.findByReaderId(readerId);
        if (readerOpt.isEmpty()) {
            return false;
        }
        Reader reader = readerOpt.get();
        return "active".equals(reader.getReaderStatus()) &&
                reader.getBorrowedCount() < reader.getBorrowLimit();
    }
}
