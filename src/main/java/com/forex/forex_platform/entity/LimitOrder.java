package com.forex.forex_platform.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * "Convert {@code amount} of {@code fromCurrency} into {@code toCurrency} once 1 from buys at least
 * {@code targetRate} to." Checked by a background job; fills at the market rate at that moment,
 * which is never worse than the target.
 */
@Entity
@Table(name = "limit_orders")
@Getter
@Setter
@NoArgsConstructor
public class LimitOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(nullable = false, length = 10)
    private String fromCurrency;

    @Column(nullable = false, length = 10)
    private String toCurrency;

    @Column(nullable = false, precision = 28, scale = 8)
    private BigDecimal amount;

    /** Minimum units of toCurrency per 1 fromCurrency. */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal targetRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** When it filled, was cancelled, or failed. */
    private LocalDateTime closedAt;

    @Column(precision = 38, scale = 18)
    private BigDecimal fillRate;

    @Column(precision = 28, scale = 8)
    private BigDecimal receivedAmount;

    /** The CONVERSION ledger entry created when the order filled. */
    private Long transactionId;

    private String failureReason;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = OrderStatus.OPEN;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
