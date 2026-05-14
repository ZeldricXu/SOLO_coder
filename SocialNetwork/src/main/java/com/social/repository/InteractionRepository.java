package com.social.repository;

import com.social.entity.Interaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InteractionRepository extends JpaRepository<Interaction, Long> {
    Optional<Interaction> findByInteractionId(String interactionId);
    List<Interaction> findByPostIdOrderByInteractionTimeDesc(String postId);
    List<Interaction> findByUserIdOrderByInteractionTimeDesc(String userId);
    long count();
}
