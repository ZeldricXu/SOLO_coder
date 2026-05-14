package com.movie.repository;

import com.movie.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUserId(String userId);

    Optional<User> findByUserPhone(String userPhone);

    boolean existsByUserId(String userId);

    boolean existsByUserPhone(String userPhone);
}
