
package com.learningplatform.repository;

import com.learningplatform.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResourceRepository extends JpaRepository<Resource, String> {

    List<Resource> findByCourseId(String courseId);

    List<Resource> findByChapterId(String chapterId);

    List<Resource> findByCourseIdAndChapterId(String courseId, String chapterId);

    List<Resource> findByCourseIdAndResourceStatus(String courseId, String status);
}
