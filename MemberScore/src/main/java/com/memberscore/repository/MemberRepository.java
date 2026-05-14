package com.memberscore.repository;

import com.memberscore.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {
    
    Optional<Member> findByMemberId(String memberId);
    
    Optional<Member> findByUserId(String userId);
    
    boolean existsByMemberId(String memberId);
    
    boolean existsByUserId(String userId);
}
