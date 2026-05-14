package com.library.librarymgmt.testdata;

import com.library.librarymgmt.entity.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static Book buildAvailableBook() {
        Book book = new Book();
        book.setBookId(UUID.randomUUID().toString());
        book.setBookName("Test Book - " + UUID.randomUUID().toString().substring(0, 8));
        book.setBookAuthor("Test Author");
        book.setBookCategory("文学");
        book.setBookPublisher("Test Publisher");
        book.setBookStatus("available");
        book.setBookStock(10);
        book.setBookAvailable(10);
        book.setRegisteredAt(Instant.now());
        return book;
    }

    public static Book buildAvailableBook(String category) {
        Book book = buildAvailableBook();
        book.setBookCategory(category);
        return book;
    }

    public static Book buildHotBook() {
        Book book = buildAvailableBook();
        book.setBookCategory("热门");
        book.setBookStock(5);
        book.setBookAvailable(5);
        return book;
    }

    public static Book buildNormalBook() {
        Book book = buildAvailableBook();
        book.setBookCategory("普通");
        book.setBookStock(20);
        book.setBookAvailable(20);
        return book;
    }

    public static Book buildBookWithZeroStock() {
        Book book = buildAvailableBook();
        book.setBookStatus("borrowed");
        book.setBookAvailable(0);
        return book;
    }

    public static Book buildBookWithCustomStock(int totalStock, int availableStock) {
        Book book = buildAvailableBook();
        book.setBookStock(totalStock);
        book.setBookAvailable(availableStock);
        book.setBookStatus(availableStock > 0 ? "available" : "borrowed");
        return book;
    }

    public static Reader buildVipReader() {
        Reader reader = new Reader();
        reader.setReaderId(UUID.randomUUID().toString());
        reader.setReaderName("VIP Reader - " + UUID.randomUUID().toString().substring(0, 8));
        reader.setReaderPhone("13800000001");
        reader.setReaderType("vip");
        reader.setReaderStatus("active");
        reader.setBorrowLimit(10);
        reader.setBorrowedCount(0);
        reader.setRegisteredAt(Instant.now());
        return reader;
    }

    public static Reader buildNormalReader() {
        Reader reader = new Reader();
        reader.setReaderId(UUID.randomUUID().toString());
        reader.setReaderName("Normal Reader - " + UUID.randomUUID().toString().substring(0, 8));
        reader.setReaderPhone("13800000002");
        reader.setReaderType("normal");
        reader.setReaderStatus("active");
        reader.setBorrowLimit(5);
        reader.setBorrowedCount(0);
        reader.setRegisteredAt(Instant.now());
        return reader;
    }

    public static Reader buildFrozenReader() {
        Reader reader = buildNormalReader();
        reader.setReaderStatus("frozen");
        return reader;
    }

    public static Reader buildReaderWithMaxBorrows() {
        Reader reader = buildNormalReader();
        reader.setBorrowedCount(reader.getBorrowLimit());
        return reader;
    }

    public static Reader buildReaderWithOverdueHistory(int overdueCount) {
        Reader reader = buildNormalReader();
        return reader;
    }

    public static Borrow buildActiveBorrow(Book book, Reader reader) {
        Borrow borrow = new Borrow();
        borrow.setBorrowId(UUID.randomUUID().toString());
        borrow.setBookId(book.getBookId());
        borrow.setReaderId(reader.getReaderId());
        borrow.setBorrowTime(Instant.now().minus(5, ChronoUnit.DAYS));
        borrow.setBorrowDue(Instant.now().plus(10, ChronoUnit.DAYS));
        borrow.setBorrowStatus("borrowed");
        return borrow;
    }

    public static Borrow buildOverdueBorrow(Book book, Reader reader, int overdueDays) {
        Borrow borrow = buildActiveBorrow(book, reader);
        borrow.setBorrowTime(Instant.now().minus(20 + overdueDays, ChronoUnit.DAYS));
        borrow.setBorrowDue(Instant.now().minus(overdueDays, ChronoUnit.DAYS));
        return borrow;
    }

    public static Borrow buildReturnedBorrow(Book book, Reader reader) {
        Borrow borrow = buildActiveBorrow(book, reader);
        borrow.setBorrowStatus("returned");
        borrow.setReturnedAt(Instant.now());
        return borrow;
    }

    public static ReturnRecord buildNormalReturnRecord(Borrow borrow) {
        ReturnRecord record = new ReturnRecord();
        record.setReturnId(UUID.randomUUID().toString());
        record.setBorrowId(borrow.getBorrowId());
        record.setBookId(borrow.getBookId());
        record.setReaderId(borrow.getReaderId());
        record.setReturnTime(Instant.now());
        record.setReturnStatus("normal");
        record.setOverdueFine(0.0);
        return record;
    }

    public static ReturnRecord buildOverdueReturnRecord(Borrow borrow, int overdueDays) {
        ReturnRecord record = buildNormalReturnRecord(borrow);
        record.setReturnStatus("overdue");
        record.setOverdueFine(overdueDays * 0.5);
        return record;
    }

    public static Reserve buildWaitingReserve(Book book, Reader reader) {
        Reserve reserve = new Reserve();
        reserve.setReserveId(UUID.randomUUID().toString());
        reserve.setBookId(book.getBookId());
        reserve.setReaderId(reader.getReaderId());
        reserve.setReserveTime(Instant.now().minus(1, ChronoUnit.HOURS));
        reserve.setReserveStatus("waiting");
        reserve.setNotified(false);
        return reserve;
    }

    public static Reserve buildNotifiedReserve(Book book, Reader reader) {
        Reserve reserve = buildWaitingReserve(book, reader);
        reserve.setReserveStatus("notified");
        reserve.setNotified(true);
        return reserve;
    }

    public static Review buildReview(Book book, Reader reader, int rating) {
        Review review = new Review();
        review.setReviewId(UUID.randomUUID().toString());
        review.setBookId(book.getBookId());
        review.setReaderId(reader.getReaderId());
        review.setReviewRating(rating);
        review.setReviewContent("Test review content");
        review.setReviewTime(Instant.now());
        return review;
    }

    public static BorrowStat buildCurrentMonthStat() {
        BorrowStat stat = new BorrowStat();
        stat.setStatId(UUID.randomUUID().toString());
        stat.setStatMonth(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")));
        stat.setBorrowCount(100);
        stat.setReturnCount(90);
        stat.setReserveCount(50);
        stat.setOverdueCount(5);
        return stat;
    }

    public static List<Book> buildMultipleBooks(int count) {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            books.add(buildAvailableBook());
        }
        return books;
    }

    public static List<Reserve> buildMultipleWaitingReserves(Book book, int count) {
        List<Reserve> reserves = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            reserves.add(buildWaitingReserve(book, buildNormalReader()));
        }
        return reserves;
    }

    public static class BookBuilder {
        private String bookId;
        private String bookName;
        private String bookAuthor;
        private String bookCategory;
        private String bookPublisher;
        private String bookStatus = "available";
        private int bookStock = 10;
        private int bookAvailable = 10;

        public BookBuilder id(String id) {
            this.bookId = id;
            return this;
        }

        public BookBuilder name(String name) {
            this.bookName = name;
            return this;
        }

        public BookBuilder category(String category) {
            this.bookCategory = category;
            return this;
        }

        public BookBuilder stock(int stock) {
            this.bookStock = stock;
            this.bookAvailable = stock;
            this.bookStatus = stock > 0 ? "available" : "borrowed";
            return this;
        }

        public BookBuilder available(int available) {
            this.bookAvailable = available;
            this.bookStatus = available > 0 ? "available" : "borrowed";
            return this;
        }

        public BookBuilder status(String status) {
            this.bookStatus = status;
            return this;
        }

        public Book build() {
            Book book = new Book();
            book.setBookId(bookId != null ? bookId : UUID.randomUUID().toString());
            book.setBookName(bookName != null ? bookName : "Test Book");
            book.setBookAuthor(bookAuthor != null ? bookAuthor : "Test Author");
            book.setBookCategory(bookCategory != null ? bookCategory : "文学");
            book.setBookPublisher(bookPublisher != null ? bookPublisher : "Test Publisher");
            book.setBookStatus(bookStatus);
            book.setBookStock(bookStock);
            book.setBookAvailable(bookAvailable);
            book.setRegisteredAt(Instant.now());
            return book;
        }
    }

    public static class ReaderBuilder {
        private String readerId;
        private String readerName;
        private String readerType = "normal";
        private String readerStatus = "active";
        private int borrowLimit = 5;
        private int borrowedCount = 0;

        public ReaderBuilder id(String id) {
            this.readerId = id;
            return this;
        }

        public ReaderBuilder type(String type) {
            this.readerType = type;
            return this;
        }

        public ReaderBuilder status(String status) {
            this.readerStatus = status;
            return this;
        }

        public ReaderBuilder borrowLimit(int limit) {
            this.borrowLimit = limit;
            return this;
        }

        public ReaderBuilder borrowedCount(int count) {
            this.borrowedCount = count;
            return this;
        }

        public Reader build() {
            Reader reader = new Reader();
            reader.setReaderId(readerId != null ? readerId : UUID.randomUUID().toString());
            reader.setReaderName(readerName != null ? readerName : "Test Reader");
            reader.setReaderType(readerType);
            reader.setReaderStatus(readerStatus);
            reader.setBorrowLimit(borrowLimit);
            reader.setBorrowedCount(borrowedCount);
            reader.setRegisteredAt(Instant.now());
            return reader;
        }
    }

    public static class BorrowBuilder {
        private String borrowId;
        private String bookId;
        private String readerId;
        private Instant borrowTime;
        private Instant borrowDue;
        private String borrowStatus = "borrowed";
        private Instant returnedAt;

        public BorrowBuilder id(String id) {
            this.borrowId = id;
            return this;
        }

        public BorrowBuilder bookId(String bookId) {
            this.bookId = bookId;
            return this;
        }

        public BorrowBuilder readerId(String readerId) {
            this.readerId = readerId;
            return this;
        }

        public BorrowBuilder borrowedDaysAgo(int days) {
            this.borrowTime = Instant.now().minus(days, ChronoUnit.DAYS);
            return this;
        }

        public BorrowBuilder dueInDays(int days) {
            this.borrowDue = Instant.now().plus(days, ChronoUnit.DAYS);
            return this;
        }

        public BorrowBuilder overdueByDays(int days) {
            this.borrowDue = Instant.now().minus(days, ChronoUnit.DAYS);
            return this;
        }

        public BorrowBuilder status(String status) {
            this.borrowStatus = status;
            return this;
        }

        public Borrow build() {
            Borrow borrow = new Borrow();
            borrow.setBorrowId(borrowId != null ? borrowId : UUID.randomUUID().toString());
            borrow.setBookId(bookId);
            borrow.setReaderId(readerId);
            borrow.setBorrowTime(borrowTime != null ? borrowTime : Instant.now().minus(5, ChronoUnit.DAYS));
            borrow.setBorrowDue(borrowDue != null ? borrowDue : Instant.now().plus(10, ChronoUnit.DAYS));
            borrow.setBorrowStatus(borrowStatus);
            borrow.setReturnedAt(returnedAt);
            return borrow;
        }
    }
}
