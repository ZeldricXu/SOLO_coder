package com.library.librarymgmt.controller;

import com.library.librarymgmt.dto.ApiResponse;
import com.library.librarymgmt.dto.BookRequest;
import com.library.librarymgmt.entity.Book;
import com.library.librarymgmt.service.BookService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @PostMapping
    public ApiResponse<Book> createBook(@Validated @RequestBody BookRequest request) {
        return ApiResponse.success(bookService.createBook(request));
    }

    @GetMapping("/{bookId}")
    public ApiResponse<Book> getBookById(@PathVariable String bookId) {
        Optional<Book> book = bookService.getBookById(bookId);
        if (book.isPresent()) {
            return ApiResponse.success(book.get());
        }
        return ApiResponse.error(404, "图书不存在");
    }

    @GetMapping
    public ApiResponse<List<Book>> getAllBooks() {
        return ApiResponse.success(bookService.getAllBooks());
    }

    @GetMapping("/category/{category}")
    public ApiResponse<List<Book>> getBooksByCategory(@PathVariable String category) {
        return ApiResponse.success(bookService.getBooksByCategory(category));
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Book>> getBooksByStatus(@PathVariable String status) {
        return ApiResponse.success(bookService.getBooksByStatus(status));
    }

    @GetMapping("/search")
    public ApiResponse<List<Book>> searchBooks(@RequestParam String keyword) {
        return ApiResponse.success(bookService.searchBooks(keyword));
    }

    @PutMapping("/{bookId}")
    public ApiResponse<Book> updateBook(@PathVariable String bookId, @Validated @RequestBody BookRequest request) {
        return ApiResponse.success(bookService.updateBook(bookId, request));
    }

    @DeleteMapping("/{bookId}")
    public ApiResponse<Void> deleteBook(@PathVariable String bookId) {
        bookService.deleteBook(bookId);
        return ApiResponse.success(200, "删除成功", null);
    }
}
