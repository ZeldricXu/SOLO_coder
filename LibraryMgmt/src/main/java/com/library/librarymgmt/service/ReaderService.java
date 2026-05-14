package com.library.librarymgmt.service;

import com.library.librarymgmt.dto.ReaderRequest;
import com.library.librarymgmt.entity.Reader;

import java.util.List;
import java.util.Optional;

public interface ReaderService {
    Reader createReader(ReaderRequest request);
    Optional<Reader> getReaderById(String readerId);
    List<Reader> getAllReaders();
    List<Reader> getReadersByStatus(String status);
    List<Reader> getReadersByType(String type);
    Reader updateReader(String readerId, ReaderRequest request);
    void deleteReader(String readerId);
    Reader updateReaderStatus(String readerId, String status);
    void increaseBorrowedCount(String readerId);
    void decreaseBorrowedCount(String readerId);
    boolean canBorrow(String readerId);
}
