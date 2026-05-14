package com.library.librarymgmt.service;

import com.library.librarymgmt.entity.HistoryLog;

import java.util.List;

public interface HistoryService {
    HistoryLog log(String logType, String refId, String bookId, String readerId, String action);
    List<HistoryLog> getLogsByType(String logType);
    List<HistoryLog> getLogsByRefId(String refId);
    List<HistoryLog> getLogsByBookId(String bookId);
    List<HistoryLog> getLogsByReaderId(String readerId);
    List<HistoryLog> getAllLogs();
}
