package com.servicedesk.entity;

import lombok.Data;

import javax.persistence.*;

@Data
@Entity
@Table(name = "agents")
public class Agent {
    @Id
    @Column(name = "agent_id", length = 50)
    private String agentId;

    @Column(name = "agent_name", length = 100, nullable = false)
    private String agentName;

    @Column(name = "agent_group", length = 50, nullable = false)
    private String agentGroup;

    @Column(name = "agent_status", length = 20, nullable = false)
    private String agentStatus;

    @Column(name = "current_tickets", nullable = false)
    private Integer currentTickets = 0;

    @Column(name = "max_tickets", nullable = false)
    private Integer maxTickets = 5;

    @Column(name = "response_avg_time")
    private Integer responseAvgTime;

    public Integer getRemainingCapacity() {
        return maxTickets - currentTickets;
    }

    public boolean canAcceptTicket() {
        return "online".equalsIgnoreCase(agentStatus) && getRemainingCapacity() > 0;
    }
}
