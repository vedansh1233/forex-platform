package com.forex.forex_platform.repository;

import com.forex.forex_platform.entity.AlertStatus;
import com.forex.forex_platform.entity.PriceAlert;
import com.forex.forex_platform.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {

    List<PriceAlert> findTop50ByUserOrderByCreatedAtDescIdDesc(User user);

    long countByUserAndStatus(User user, AlertStatus status);

    List<PriceAlert> findByStatusOrderByIdAsc(AlertStatus status);

    /**
     * Triggers an alert only if it is still ACTIVE, so a trigger and a cancel racing each other
     * can't both win. Returns the number of rows changed (0 or 1).
     */
    @Modifying
    @Transactional
    @Query("""
            update PriceAlert a set a.status = com.forex.forex_platform.entity.AlertStatus.TRIGGERED,
                   a.triggeredAt = :at, a.triggeredRate = :rate, a.seen = false
            where a.id = :id and a.status = com.forex.forex_platform.entity.AlertStatus.ACTIVE""")
    int trigger(@Param("id") Long id, @Param("rate") BigDecimal rate, @Param("at") LocalDateTime at);

    /** Cancels an alert only if it belongs to the user and is still ACTIVE. Returns rows changed. */
    @Modifying
    @Transactional
    @Query("""
            update PriceAlert a set a.status = com.forex.forex_platform.entity.AlertStatus.CANCELLED
            where a.id = :id and a.user = :user and a.status = com.forex.forex_platform.entity.AlertStatus.ACTIVE""")
    int cancel(@Param("id") Long id, @Param("user") User user);

    /** Marks every triggered, unseen alert of the user as seen. Returns rows changed. */
    @Modifying
    @Transactional
    @Query("""
            update PriceAlert a set a.seen = true
            where a.user = :user and a.status = com.forex.forex_platform.entity.AlertStatus.TRIGGERED and a.seen = false""")
    int markSeen(@Param("user") User user);
}
