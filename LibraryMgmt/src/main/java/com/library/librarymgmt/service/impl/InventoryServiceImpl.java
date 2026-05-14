package com.library.librarymgmt.service.impl;

import com.library.librarymgmt.service.BookService;
import com.library.librarymgmt.service.InventoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryServiceImpl implements InventoryService {

    private final BookService bookService;

    public InventoryServiceImpl(BookService bookService) {
        this.bookService = bookService;
    }

    @Override
    @Transactional
    public void decreaseStock(String bookId, int count) {
        bookService.decreaseAvailable(bookId, count);
    }

    @Override
    @Transactional
    public void increaseStock(String bookId, int count) {
        bookService.increaseAvailable(bookId, count);
    }

    @Override
    public boolean checkAvailable(String bookId, int count) {
        return bookService.getBookById(bookId)
                .map(book -> book.getBookAvailable() >= count && "available".equals(book.getBookStatus()))
                .orElse(false);
    }

    @Override
    @Transactional
    public void updateBookStatusBasedOnStock(String bookId) {
        bookService.getBookById(bookId).ifPresent(book -> {
            String newStatus = book.getBookAvailable() > 0 ? "available" : "borrowed";
            bookService.updateBookStatus(bookId, newStatus);
        });
    }
}
