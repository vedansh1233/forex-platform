package com.forex.forex_platform.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Amount of one non-base asset (currency or crypto) held in a wallet. The base currency (USD) cash
 * balance stays in {@link Wallet#getBalance()}.
 */
@Entity
@Table(name = "holdings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"wallet_id", "currency"}))
@Getter
@Setter
@NoArgsConstructor
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(nullable = false, precision = 28, scale = 8)
    private BigDecimal amount;

    public Holding(Wallet wallet, String currency) {
        this.wallet = wallet;
        this.currency = currency;
        this.amount = BigDecimal.ZERO;
    }
}
