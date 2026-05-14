package com.library.librarymgmt.repository;

import com.library.librarymgmt.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, String> {
    Optional<Review> findByReviewId(String reviewId);
    List<Review> findByBookId(String bookId);
    List<Review> findByReaderId(String readerId);
    List<Review> findByBookIdAndReaderId(String bookId, String readerId);
    boolean existsByReviewId(String reviewId);
}
