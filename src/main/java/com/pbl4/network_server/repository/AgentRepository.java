package com.pbl4.network_server.repository;

import com.pbl4.network_server.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {
    List<Agent> findByStatus(String status);

    // Lấy danh sách Agent ONLINE nhưng quá thời gian chỉ định không gửi dữ liệu
    List<Agent> findByStatusAndLastSeenBefore(String status, LocalDateTime thresholdTime);
}