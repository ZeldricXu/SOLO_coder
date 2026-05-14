package com.servicedesk.repository;

import com.servicedesk.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {
    List<Agent> findByAgentGroup(String agentGroup);
    List<Agent> findByAgentStatus(String agentStatus);
    
    @Query("SELECT a FROM Agent a WHERE a.agentStatus = 'online' AND a.agentGroup = :group AND a.currentTickets < a.maxTickets")
    List<Agent> findAvailableAgentsByGroup(String group);
    
    @Query("SELECT a FROM Agent a WHERE a.agentStatus = 'online' AND a.currentTickets < a.maxTickets")
    List<Agent> findAllAvailableAgents();
    
    boolean existsByAgentId(String agentId);
}
