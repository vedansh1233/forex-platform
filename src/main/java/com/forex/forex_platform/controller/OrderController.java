package com.forex.forex_platform.controller;

import com.forex.forex_platform.dto.OrderResponse;
import com.forex.forex_platform.dto.PlaceOrderRequest;
import com.forex.forex_platform.service.OrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Limit orders: convert automatically once a target rate is reached. */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse place(Authentication authentication, @Valid @RequestBody PlaceOrderRequest request) {
        return orderService.place(authentication.getName(), request);
    }

    /** The 50 most recent orders, newest first. */
    @GetMapping
    public List<OrderResponse> list(Authentication authentication) {
        return orderService.list(authentication.getName());
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(Authentication authentication, @PathVariable Long id) {
        return orderService.cancel(authentication.getName(), id);
    }
}
