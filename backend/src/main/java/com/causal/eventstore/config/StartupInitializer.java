package com.causal.eventstore.config;

import com.causal.eventstore.config.AppConfig;
import com.causal.eventstore.service.ClusterService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StartupInitializer {

    private final ClusterService clusterService;
    private final AppConfig appConfig;

    public StartupInitializer(ClusterService clusterService, AppConfig appConfig) {
        this.clusterService = clusterService;
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void init() {
        String nodeId = appConfig.getReplication().getNodeId();
        String role = appConfig.getReplication().getRole();
        clusterService.registerNode(nodeId, role, "localhost", 9090, 8080);
        log.info("Node registered: id={}, role={}", nodeId, role);
    }
}
