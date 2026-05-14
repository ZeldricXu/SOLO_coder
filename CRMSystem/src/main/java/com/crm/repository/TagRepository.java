package com.crm.repository;

import com.crm.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, String> {
    Optional<Tag> findByTagId(String tagId);
    List<Tag> findByTagStatus(String tagStatus);
    List<Tag> findByTagType(String tagType);
}
