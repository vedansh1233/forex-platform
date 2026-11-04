package com.forex.forex_platform.repository;

import com.forex.forex_platform.entity.LimitOrder;
import com.forex.forex_platform.entity.OrderStatus;
import com.forex.forex_platform.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LimitOrderRepository extends JpaRepository<LimitOrder, Long> {

    List<LimitOrder> findTop50ByWalletOrderByCreatedAtDescIdDesc(Wallet wallet);

    long countByWalletAndStatus(Wallet wallet, OrderStatus status);

    List<LimitOrder> findByStatusOrderByIdAsc(OrderStatus status);

    @Query("select o.wallet.id from LimitOrder o where o.id = :id")
    Optional<Long> findWalletIdById(@Param("id") Long id);
}
