package com.cms.repository;

import com.cms.entity.Content;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ContentRepository extends JpaRepository<Content, String> {

    List<Content> findByContentStatus(String contentStatus);

    List<Content> findByContentCategory(String contentCategory);

    List<Content> findByContentAuthor(String contentAuthor);

    @Query("SELECT c FROM Content c WHERE c.contentStatus = :status AND c.contentCategory = :category")
    List<Content> findByStatusAndCategory(@Param("status") String status, @Param("category") String category);

    @Query("SELECT COUNT(c) FROM Content c WHERE c.contentStatus = :status")
    long countByStatus(@Param("status") String status);

    @Query("SELECT COUNT(c) FROM Content c")
    long countAll();
}
