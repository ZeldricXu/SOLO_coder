package com.library.librarymgmt.service.impl;

import com.library.librarymgmt.config.LibraryConfig;
import com.library.librarymgmt.dto.BookRequest;
import com.library.librarymgmt.entity.Book;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.BookRepository;
import com.library.librarymgmt.service.BookService;
import com.library.librarymgmt.service.CategoryService;
import com.library.librarymgmt.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class BookServiceImpl implements BookService {

    private static final Logger logger = LoggerFactory.getLogger(BookServiceImpl.class);

    private final BookRepository bookRepository;
    private final CategoryService categoryService;

    public BookServiceImpl(BookRepository bookRepository, CategoryService categoryService) {
        this.bookRepository = bookRepository;
        this.categoryService = categoryService;
    }

    @Override
    @Transactional
    public Book createBook(BookRequest request) {
        categoryService.validateCategory(request.getBook_category());

        Book book = new Book();
        book.setBookId(IdGenerator.generateBookId());
        book.setBookName(request.getBook_name());
        book.setBookAuthor(request.getBook_author());
        book.setBookCategory(request.getBook_category());
        book.setBookPublisher(request.getBook_publisher());
        book.setBookStock(request.getBook_stock());
        book.setBookAvailable(request.getBook_stock());
        book.setBookStatus(request.getBook_stock() > 0 ? "available" : "unavailable");

        Book savedBook = bookRepository.save(book);
        logger.info("图书创建成功: bookId={}, category={}, stock={}",
                savedBook.getBookId(), savedBook.getBookCategory(), savedBook.getBookStock());

        return savedBook;
    }

    @Override
    public Optional<Book> getBookById(String bookId) {
        return bookRepository.findByBookId(bookId);
    }

    @Override
    public List<Book> getAllBooks() {
        return bookRepository.findAll();
    }

    @Override
    public List<Book> getBooksByCategory(String category) {
        return bookRepository.findByBookCategory(category);
    }

    @Override
    public List<Book> getBooksByStatus(String status) {
        return bookRepository.findByBookStatus(status);
    }

    @Override
    public List<Book> searchBooks(String keyword) {
        return bookRepository.findByBookNameContaining(keyword);
    }

    @Override
    @Transactional
    public Book updateBook(String bookId, BookRequest request) {
        Book book = bookRepository.findByBookId(bookId)
                .orElseThrow(() -> new LibraryException(404, "图书不存在"));

        if (request.getBook_category() != null && !request.getBook_category().equals(book.getBookCategory())) {
            categoryService.validateCategory(request.getBook_category());
            book.setBookCategory(request.getBook_category());
        }

        book.setBookName(request.getBook_name());
        book.setBookAuthor(request.getBook_author());
        if (request.getBook_publisher() != null) {
            book.setBookPublisher(request.getBook_publisher());
        }

        Book updatedBook = bookRepository.save(book);
        logger.info("图书更新成功: bookId={}, category={}", updatedBook.getBookId(), updatedBook.getBookCategory());

        return updatedBook;
    }

    @Override
    @Transactional
    public void deleteBook(String bookId) {
        Book book = bookRepository.findByBookId(bookId)
                .orElseThrow(() -> new LibraryException(404, "图书不存在"));
        bookRepository.delete(book);
        logger.info("图书删除成功: bookId={}", bookId);
    }

    @Override
    @Transactional
    public Book updateBookStatus(String bookId, String status) {
        Book book = bookRepository.findByBookId(bookId)
                .orElseThrow(() -> new LibraryException(404, "图书不存在"));
        book.setBookStatus(status);
        return bookRepository.save(book);
    }

    @Override
    @Transactional
    public void increaseAvailable(String bookId, int count) {
        Book book = bookRepository.findByBookId(bookId)
                .orElseThrow(() -> new LibraryException(404, "图书不存在"));
        int newAvailable = book.getBookAvailable() + count;
        if (newAvailable > book.getBookStock()) {
            throw new LibraryException(400, "可用库存不能超过总库存");
        }
        book.setBookAvailable(newAvailable);
        if (newAvailable > 0) {
            book.setBookStatus("available");
        }
        bookRepository.save(book);
    }

    @Override
    @Transactional
    public void decreaseAvailable(String bookId, int count) {
        Book book = bookRepository.findByBookId(bookId)
                .orElseThrow(() -> new LibraryException(404, "图书不存在"));
        if (book.getBookAvailable() < count) {
            throw new LibraryException(400, "库存不足");
        }
        int newAvailable = book.getBookAvailable() - count;
        book.setBookAvailable(newAvailable);
        if (newAvailable == 0) {
            book.setBookStatus("borrowed");
        }
        bookRepository.save(book);
    }
}
