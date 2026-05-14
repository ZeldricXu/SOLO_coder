package com.fitnesscenter.repository;

import com.fitnesscenter.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, String> {
    
    Optional<Member> findByMemberId(String memberId);
    
    Optional<Member> findByMemberPhone(String memberPhone);
    
    List<Member> findByMemberStatus(String memberStatus);
    
    List<Member> findByMemberType(String memberType);
    
    boolean existsByMemberId(String memberId);
    
    boolean existsByMemberPhone(String memberPhone);
}
