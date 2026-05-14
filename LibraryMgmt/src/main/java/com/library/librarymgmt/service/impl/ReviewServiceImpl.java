package com.library.librarymgmt.service.impl;

import com.library.librarymgmt.dto.ReviewRequest;
import com.library.librarymgmt.entity.Review;
import com.library.librarymgmt.exception.LibraryException;
import com.library.librarymgmt.repository.ReviewRepository;
import com.library.librarymgmt.service.BookService;
import com.library.librarymgmt.service.ReaderService;
import com.library.librarymgmt.service.ReviewService;
import com.library.librarymgmt.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepository;
    private final BookService bookService;
    private final ReaderService readerService;

    public ReviewServiceImpl(ReviewRepository reviewRepository,
                             BookService bookService,
                             ReaderService readerService) {
        this.reviewRepository = reviewRepository;
        this.bookService = bookService;
        this.readerService = readerService;
    }

    @Override
    @Transactional
    public Review createReview(ReviewRequest request) {
        if (!bookService.getBookById(request.getBook_id()).isPresent()) {
            throw new LibraryException(404, "图书不存在");
        }
        if (!readerService.getReaderById(request.getReader_id()).isPresent()) {
            throw new LibraryException(404, "读者不存在");
        }

        Review review = new Review();
        review.setReviewId(IdGenerator.generateReviewId());
        review.setBookId(request.getBook_id());
        review.setReaderId(request.getReader_id());
        review.setReviewRating(request.getReview_rating());
        review.setReviewContent(request.getReview_content());
        return reviewRepository.save(review);
    }

    @Override
    public Optional<Review> getReviewById(String reviewId) {
        return reviewRepository.findByReviewId(reviewId);
    }

    @Override
    public List<Review> getAllReviews() {
        return reviewRepository.findAll();
    }

    @Override
    public List<Review> getReviewsByBookId(String bookId) {
        return reviewRepository.findByBookId(bookId);
    }

    @Override
    public List<Review> getReviewsByReaderId(String readerId) {
        return reviewRepository.findByReaderId(readerId);
    }

    @Override
    public List<Review> getReviewsByBookIdAndReaderId(String bookId, String readerId) {
        return reviewRepository.findByBookIdAndReaderId(bookId, readerId);
    }

    @Override
    @Transactional
    public void deleteReview(String reviewId) {
        Review review = reviewRepository.findByReviewId(reviewId)
                .orElseThrow(() -> new LibraryException(404, "评价不存在"));
        reviewRepository.delete(review);
    }

    @Override
    public double getAverageRating(String bookId) {
        List<Review> reviews = getReviewsByBookId(bookId);
        if (reviews.isEmpty()) {
            return 0.0;
        }
        double sum = reviews.stream()
                .mapToInt(Review::getReviewRating)
                .sum();
        return sum / reviews.size();
    }
}
