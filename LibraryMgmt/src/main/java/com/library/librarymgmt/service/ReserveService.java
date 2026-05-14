package com.library.librarymgmt.service;

import com.library.librarymgmt.dto.ReserveRequest;
import com.library.librarymgmt.dto.ReserveResult;
import com.library.librarymgmt.entity.Reserve;

import java.util.List;
import java.util.Optional;

public interface ReserveService {
    ReserveResult createReserve(ReserveRequest request);
    Optional<Reserve> getReserveById(String reserveId);
    List<Reserve> getAllReserves();
    List<Reserve> getReservesByBookId(String bookId);
    List<Reserve> getReservesByReaderId(String readerId);
    List<Reserve> getWaitingReservesByBookId(String bookId);
    Reserve updateReserveStatus(String reserveId, String status);
    void notifyWaitingReaders(String bookId);
}
