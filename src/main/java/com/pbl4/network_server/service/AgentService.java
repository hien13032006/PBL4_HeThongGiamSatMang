package com.pbl4.network_server.service;

import com.pbl4.network_server.entity.Agent;
import com.pbl4.network_server.repository.AgentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AgentService {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void markAgentOffline(String agentId) {
        Optional<Agent> optionalAgent = agentRepository.findById(agentId);
        if (optionalAgent.isPresent()) {
            Agent agent = optionalAgent.get();
            if ("ONLINE".equals(agent.getStatus())) {
                agent.setStatus("OFFLINE");
                agentRepository.save(agent);
                System.out.println("[OFFLINE] Agent " + agentId + " ngắt kết nối TCP.");

                // Bắn WebSocket thông báo để Frontend tự động cập nhật
                messagingTemplate.convertAndSend("/topic/agents", agentRepository.findAll());
            }
        }
    }
}