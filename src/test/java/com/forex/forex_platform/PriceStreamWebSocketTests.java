package com.forex.forex_platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forex.forex_platform.service.CryptoPriceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The {@code /ws/prices} stream, over a real WebSocket connection to a running server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PriceStreamWebSocketTests {

    @LocalServerPort
    private int port;

    @Autowired
    private CryptoPriceService cryptoPriceService;

    @Autowired
    private ObjectMapper objectMapper;

    private final StandardWebSocketClient client = new StandardWebSocketClient();

    private WebSocketSession connect(String origin, BlockingQueue<String> messages) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        return client.execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        messages.add(message.getPayload());
                    }
                }, headers, URI.create("ws://localhost:" + port + "/ws/prices"))
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void sendsSnapshotOnConnectAndEveryRefresh() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocketSession session = connect("http://localhost:5173", messages);
        try {
            JsonNode snapshot = objectMapper.readTree(messages.poll(5, TimeUnit.SECONDS));
            assertThat(snapshot.get("source").asText()).isEqualTo("SIMULATED");
            assertThat(snapshot.get("prices")).hasSize(5);
            assertThat(snapshot.at("/prices/0/symbol").asText()).isEqualTo("BTC");

            cryptoPriceService.refresh();
            JsonNode update = objectMapper.readTree(messages.poll(5, TimeUnit.SECONDS));
            assertThat(update.get("prices")).hasSize(5);
            assertThat(update.get("updatedAt").asText()).isNotBlank();
        } finally {
            session.close();
        }
    }

    @Test
    void rejectsUnknownOrigins() {
        assertThatThrownBy(() -> connect("https://evil.example", new LinkedBlockingQueue<>()))
                .hasMessageContaining("403");
    }
}
