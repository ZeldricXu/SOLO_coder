package com.cicd.server.repository;

import com.cicd.server.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByProviderAndProviderId(String provider, String providerId);

    @Query("SELECT u FROM User u JOIN u.roles ur WHERE ur.role = 'PLATFORM_ADMIN'")
    List<User> findPlatformAdmins();
}
