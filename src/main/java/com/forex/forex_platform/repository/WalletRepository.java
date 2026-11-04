package com.forex.forex_platform.repository;

import com.forex.forex_platform.entity.User;
import com.forex.forex_platform.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUser(User user);

    boolean existsByUser(User user);

    /** Row-locks the wallet (SELECT ... FOR UPDATE) so concurrent conversions can't overspend. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.user = :user")
    Optional<Wallet> findByUserForUpdate(@Param("user") User user);
}
