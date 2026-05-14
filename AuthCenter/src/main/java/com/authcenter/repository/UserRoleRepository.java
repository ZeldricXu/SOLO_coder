package com.authcenter.repository;

import com.authcenter.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, String> {
    
    List<UserRole> findByUserId(String userId);
}