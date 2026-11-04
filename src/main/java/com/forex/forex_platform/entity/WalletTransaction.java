package com.forex.forex_platform.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable ledger entry. For a DEPOSIT only the "to" side is set.
 */
@Entity
@Table(name = "wallet_transactions",
        indexes = @Index(name = "idx_wallet_tx_wallet_created", columnList = "wallet_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(length = 3)
    private String fromCurrency;

    @Column(precision = 19, scale = 4)
    private BigDecimal fromAmount;

    @Column(nullable = false, length = 3)
    private String toCurrency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal toAmount;

    /** Units of toCurrency per 1 fromCurrency; null for deposits. */
    @Column(precision = 19, scale = 8)
    private BigDecimal rate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
