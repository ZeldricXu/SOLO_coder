package com.library.librarymgmt.service;

import com.library.librarymgmt.dto.BookRequest;
import com.library.librarymgmt.entity.Book;

import java.util.List;
import java.util.Optional;

public interface BookService {
    Book createBook(BookRequest request);
    Optional<Book> getBookById(String bookId);
    List<Book> getAllBooks();
    List<Book> getBooksByCategory(String category);
    List<Book> getBooksByStatus(String status);
    List<Book> searchBooks(String keyword);
    Book updateBook(String bookId, BookRequest request);
    void deleteBook(String bookId);
    Book updateBookStatus(String bookId, String status);
    void increaseAvailable(String bookId, int count);
    void decreaseAvailable(String bookId, int count);
}
