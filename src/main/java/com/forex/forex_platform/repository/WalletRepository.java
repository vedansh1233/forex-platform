package com.forex.forex_platform.repository;

import com.forex.forex_platform.entity.User;
import com.forex.forex_platform.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUser(User user);

    boolean existsByUser(User user);
}
