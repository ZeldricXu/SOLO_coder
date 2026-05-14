package com.library.librarymgmt.service;

import com.library.librarymgmt.dto.ReviewRequest;
import com.library.librarymgmt.entity.Review;

import java.util.List;
import java.util.Optional;

public interface ReviewService {
    Review createReview(ReviewRequest request);
    Optional<Review> getReviewById(String reviewId);
    List<Review> getAllReviews();
    List<Review> getReviewsByBookId(String bookId);
    List<Review> getReviewsByReaderId(String readerId);
    List<Review> getReviewsByBookIdAndReaderId(String bookId, String readerId);
    void deleteReview(String reviewId);
    double getAverageRating(String bookId);
}
