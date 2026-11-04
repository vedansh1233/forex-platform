package com.forex.forex_platform.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forex.forex_platform.dto.CryptoPricesResponse;
import com.forex.forex_platform.event.CryptoPricesUpdatedEvent;
import com.forex.forex_platform.service.CryptoPriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /ws/prices}: a public, server-to-client stream of crypto prices. A client receives the
 * current snapshot on connect, then every refresh as a JSON {@link CryptoPricesResponse}.
 * Incoming messages are ignored.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceStreamHandler extends TextWebSocketHandler {

    /** Drop a client whose sends block longer than this, or who falls this far behind. */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 256 * 1024;

    private final CryptoPriceService cryptoPriceService;
    private final ObjectMapper objectMapper;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // The decorator makes sends thread-safe and protects us from slow clients
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        sessions.put(session.getId(), safe);
        try {
            send(safe, toJson(cryptoPriceService.latest()));
        } catch (RuntimeException e) {
            log.debug("No initial snapshot for {}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @EventListener
    public void onPricesUpdated(CryptoPricesUpdatedEvent event) {
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage message = toJson(event.prices());
        sessions.values().forEach(session -> send(session, message));
    }

    /** Number of connected clients (for tests and diagnostics). */
    public int connectedClients() {
        return sessions.size();
    }

    private void send(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return;
        }
        try {
            session.sendMessage(message);
        } catch (IOException | RuntimeException e) {
            log.debug("Dropping WebSocket client {}: {}", session.getId(), e.getMessage());
            sessions.remove(session.getId());
        }
    }

    private TextMessage toJson(CryptoPricesResponse prices) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(prices));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize prices", e);
        }
    }
}
