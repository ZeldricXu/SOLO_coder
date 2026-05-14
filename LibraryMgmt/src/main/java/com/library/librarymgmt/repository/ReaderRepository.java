package com.library.librarymgmt.repository;

import com.library.librarymgmt.entity.Reader;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReaderRepository extends JpaRepository<Reader, String> {
    Optional<Reader> findByReaderId(String readerId);
    List<Reader> findByReaderStatus(String status);
    List<Reader> findByReaderType(String type);
    boolean existsByReaderId(String readerId);
}
