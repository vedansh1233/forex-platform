package com.forex.forex_platform.config;

import com.forex.forex_platform.websocket.PriceStreamHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

/** Plain WebSocket (no STOMP): browsers connect with {@code new WebSocket(".../ws/prices")}. */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final PriceStreamHandler priceStreamHandler;

    /** Same allowed origins as the REST API's CORS configuration. */
    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(priceStreamHandler, "/ws/prices")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }
}
