package com.social.repository;

import com.social.entity.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {
    Optional<Post> findByPostId(String postId);
    List<Post> findByUserIdOrderByPostTimeDesc(String userId);
    List<Post> findByPostStatusOrderByPostTimeDesc(String status);
    long count();
}
