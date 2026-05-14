package com.library.librarymgmt.repository;

import com.library.librarymgmt.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookRepository extends JpaRepository<Book, String> {
    Optional<Book> findByBookId(String bookId);
    List<Book> findByBookCategory(String category);
    List<Book> findByBookStatus(String status);
    List<Book> findByBookNameContaining(String keyword);
    boolean existsByBookId(String bookId);
}
