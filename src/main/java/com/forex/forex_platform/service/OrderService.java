package com.forex.forex_platform.service;

import com.forex.forex_platform.dto.OrderResponse;
import com.forex.forex_platform.dto.PlaceOrderRequest;

import java.util.List;

public interface OrderService {

    OrderResponse place(String email, PlaceOrderRequest request);

    /** The user's 50 most recent orders, newest first. */
    List<OrderResponse> list(String email);

    OrderResponse cancel(String email, Long orderId);

    /**
     * Checks every open order against the latest rates and fills those whose target is reached.
     * Called by the background job; returns how many orders filled.
     */
    int matchOpenOrders();
}
