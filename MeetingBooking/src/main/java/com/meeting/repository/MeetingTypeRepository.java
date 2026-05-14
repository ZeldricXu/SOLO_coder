package com.meeting.repository;

import com.meeting.entity.MeetingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MeetingTypeRepository extends JpaRepository<MeetingType, String> {

    Optional<MeetingType> findByTypeId(String typeId);

    Optional<MeetingType> findByTypeCode(String typeCode);

    List<MeetingType> findByStatus(String status);

    List<MeetingType> findByStatusOrderByTypeNameAsc(String status);

    boolean existsByTypeCode(String typeCode);
}
