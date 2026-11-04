package com.forex.forex_platform.controller;

import com.forex.forex_platform.dto.AlertResponse;
import com.forex.forex_platform.dto.CreateAlertRequest;
import com.forex.forex_platform.service.AlertService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Price alerts: get notified when a pair crosses a target rate. */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
@Tag(name = "Alerts")
public class AlertController {

    private final AlertService alertService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertResponse create(Authentication authentication, @Valid @RequestBody CreateAlertRequest request) {
        return alertService.create(authentication.getName(), request);
    }

    /** The 50 most recent alerts, newest first. Unseen TRIGGERED alerts are notifications. */
    @GetMapping
    public List<AlertResponse> list(Authentication authentication) {
        return alertService.list(authentication.getName());
    }

    @PostMapping("/{id}/cancel")
    public AlertResponse cancel(Authentication authentication, @PathVariable Long id) {
        return alertService.cancel(authentication.getName(), id);
    }

    /** Clears the notification badge: marks all triggered alerts as seen. */
    @PostMapping("/mark-seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markSeen(Authentication authentication) {
        alertService.markSeen(authentication.getName());
    }
}
