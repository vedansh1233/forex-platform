package com.forex.forex_platform.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Notifies the user once when {@code baseCurrency/quoteCurrency} crosses {@code targetRate}. */
@Entity
@Table(name = "price_alerts")
@Getter
@Setter
@NoArgsConstructor
public class PriceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 10)
    private String baseCurrency;

    @Column(nullable = false, length = 10)
    private String quoteCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AlertDirection direction;

    /** Units of quoteCurrency per 1 baseCurrency. */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal targetRate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertStatus status;

    /** Whether the user has seen this alert's notification since it triggered. */
    @Column(nullable = false)
    private boolean seen;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime triggeredAt;

    @Column(precision = 38, scale = 18)
    private BigDecimal triggeredRate;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = AlertStatus.ACTIVE;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
