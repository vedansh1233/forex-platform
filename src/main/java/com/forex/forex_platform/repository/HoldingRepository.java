package com.forex.forex_platform.repository;

import com.forex.forex_platform.entity.Holding;
import com.forex.forex_platform.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    List<Holding> findByWallet(Wallet wallet);
}
