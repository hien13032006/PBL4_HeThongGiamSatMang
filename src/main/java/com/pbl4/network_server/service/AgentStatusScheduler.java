package com.pbl4.network_server.service;

import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.repository.AgentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class AgentStatusScheduler {

    @Autowired
    private AgentRepository agentRepository;

    // Chạy định kỳ 5 giây 1 lần
    @Scheduled(fixedRate = 5000)
    public void checkAgentStatus() {
        // Ngưỡng 10 giây không có dữ liệu/Heartbeat sẽ coi là OFFLINE
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(10);
        List<Agent> offlineAgents = agentRepository.findByStatusAndLastSeenBefore("ONLINE", threshold);

        for (Agent agent : offlineAgents) {
            agent.setStatus("OFFLINE");
            agentRepository.save(agent);
            System.out.println("[CAP NHAT] Agent " + agent.getAgentId() + " da ngat ket noi (OFFLINE).");
        }
    }
}