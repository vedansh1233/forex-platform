package com.forex.forex_platform.entity;

public enum TransactionType {
    /** Demo funds credited when the wallet is opened. */
    DEPOSIT,
    /** Exchange of one currency for another at the current rate. */
    CONVERSION
}
