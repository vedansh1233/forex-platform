package com.forex.forex_platform.event;

import com.forex.forex_platform.dto.CryptoPricesResponse;

/** Published after every crypto price refresh; the WebSocket handler forwards it to clients. */
public record CryptoPricesUpdatedEvent(CryptoPricesResponse prices) {
}
