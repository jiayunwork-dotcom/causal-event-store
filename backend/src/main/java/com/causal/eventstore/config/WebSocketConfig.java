package com.causal.eventstore.config;

import com.causal.eventstore.service.WebSocketPushService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketPushService webSocketPushService;

    public WebSocketConfig(WebSocketPushService webSocketPushService) {
        this.webSocketPushService = webSocketPushService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketPushService, "/ws/events")
                .setAllowedOrigins("*");
    }
}
