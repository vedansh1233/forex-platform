package com.forex.forex_platform.service;

import com.forex.forex_platform.dto.AlertResponse;
import com.forex.forex_platform.dto.CreateAlertRequest;

import java.util.List;

public interface AlertService {

    AlertResponse create(String email, CreateAlertRequest request);

    /** The user's 50 most recent alerts, newest first. */
    List<AlertResponse> list(String email);

    AlertResponse cancel(String email, Long alertId);

    /** Marks every triggered alert of the user as seen (clears the notification badge). */
    void markSeen(String email);

    /** Triggers active alerts whose condition is met. Called by the background job; returns how many triggered. */
    int checkActiveAlerts();
}
