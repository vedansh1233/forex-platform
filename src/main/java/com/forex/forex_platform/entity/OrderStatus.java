package com.forex.forex_platform.entity;

public enum OrderStatus {
    /** Waiting for the rate to reach the target. */
    OPEN,
    /** Executed; see the linked ledger transaction. */
    FILLED,
    /** Cancelled by the user before it filled. */
    CANCELLED,
    /** The target was reached but the order couldn't execute (e.g. not enough funds by then). */
    FAILED
}
