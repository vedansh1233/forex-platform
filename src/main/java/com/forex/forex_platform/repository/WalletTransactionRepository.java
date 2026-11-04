package com.forex.forex_platform.repository;

import com.forex.forex_platform.entity.Wallet;
import com.forex.forex_platform.entity.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    /** Newest first; id breaks ties between entries created in the same instant. */
    Page<WalletTransaction> findByWalletOrderByCreatedAtDescIdDesc(Wallet wallet, Pageable pageable);
}
