package com.mobilestore.repository;

import com.mobilestore.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, String> {

    Optional<UserRole> findByUserId(String userId);

    @Query("SELECT ur FROM UserRole ur WHERE ur.userId = :userId AND ur.status = 'active'")
    Optional<UserRole> findActiveByUserId(@Param("userId") String userId);

    @Query("SELECT ur FROM UserRole ur WHERE ur.roleCode = :roleCode AND ur.status = 'active'")
    List<UserRole> findActiveByRoleCode(@Param("roleCode") String roleCode);

    @Query("SELECT ur FROM UserRole ur WHERE ur.roleCode IN :roleCodes AND ur.status = 'active'")
    List<UserRole> findActiveByRoleCodes(@Param("roleCodes") List<String> roleCodes);

    boolean existsByUserIdAndStatus(String userId, String status);

    @Query("SELECT ur.permissions FROM UserRole ur WHERE ur.userId = :userId AND ur.status = 'active'")
    Optional<String> findPermissionsByUserId(@Param("userId") String userId);
}
