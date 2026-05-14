package com.library.librarymgmt.service;

public interface InventoryService {
    void decreaseStock(String bookId, int count);
    void increaseStock(String bookId, int count);
    boolean checkAvailable(String bookId, int count);
    void updateBookStatusBasedOnStock(String bookId);
}
