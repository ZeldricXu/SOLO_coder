package com.authcenter.repository;

import com.authcenter.entity.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {
    
    Optional<AuthSession> findByToken(String token);
    
    List<AuthSession> findByUserIdAndStatus(String userId, String status);
    
    Optional<AuthSession> findBySessionId(String sessionId);
}