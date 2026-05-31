package com.web3platform.catalog.infrastructure.persistence.mybatis.mapper;

import com.web3platform.catalog.infrastructure.persistence.mybatis.entity.ServiceEntryPO;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ServiceEntryMapper {
    @Insert("INSERT INTO service_entry (id, name, description, language, owner, team, " +
            "repository_url, api_doc_url, status, version, tags, created_at, updated_at) " +
            "VALUES (#{id}, #{name}, #{description}, #{language}, #{owner}, #{team}, " +
            "#{repositoryUrl}, #{apiDocUrl}, #{status}, #{version}, #{tags}, #{createdAt}, #{updatedAt})")
    void insert(ServiceEntryPO po);

    @Update("UPDATE service_entry SET name = #{name}, description = #{description}, " +
            "language = #{language}, owner = #{owner}, team = #{team}, " +
            "repository_url = #{repositoryUrl}, api_doc_url = #{apiDocUrl}, " +
            "status = #{status}, version = #{version}, tags = #{tags}, updated_at = #{updatedAt} " +
            "WHERE id = #{id}")
    void update(ServiceEntryPO po);

    @Select("SELECT * FROM service_entry WHERE id = #{id}")
    @Results(id = "ServiceEntryResultMap", value = {
        @Result(property = "repositoryUrl", column = "repository_url"),
        @Result(property = "apiDocUrl", column = "api_doc_url"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<ServiceEntryPO> findById(String id);

    @Select("SELECT * FROM service_entry WHERE name = #{name}")
    @ResultMap("ServiceEntryResultMap")
    Optional<ServiceEntryPO> findByName(String name);

    @Select("SELECT * FROM service_entry")
    @ResultMap("ServiceEntryResultMap")
    List<ServiceEntryPO> findAll();

    @Select("SELECT * FROM service_entry WHERE language = #{language}")
    @ResultMap("ServiceEntryResultMap")
    List<ServiceEntryPO> findByLanguage(String language);

    @Select("SELECT * FROM service_entry WHERE team = #{team}")
    @ResultMap("ServiceEntryResultMap")
    List<ServiceEntryPO> findByTeam(String team);

    @Select("SELECT * FROM service_entry WHERE status = #{status}")
    @ResultMap("ServiceEntryResultMap")
    List<ServiceEntryPO> findByStatus(String status);

    @Delete("DELETE FROM service_entry WHERE id = #{id}")
    void delete(String id);

    @Select("SELECT COUNT(*) FROM service_entry WHERE id = #{id}")
    boolean exists(String id);
}
