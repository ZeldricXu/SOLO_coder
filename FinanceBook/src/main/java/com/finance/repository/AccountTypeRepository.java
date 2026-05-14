package com.finance.repository;

import com.finance.entity.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountTypeRepository extends JpaRepository<AccountType, String> {
    List<AccountType> findByTypeStatus(String typeStatus);
    Optional<AccountType> findByTypeCode(String typeCode);
}
