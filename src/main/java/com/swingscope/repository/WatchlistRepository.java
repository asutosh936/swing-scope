package com.swingscope.repository;

import com.swingscope.domain.scan.WatchlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistRepository extends JpaRepository<WatchlistEntry, Long> {

    List<WatchlistEntry> findAllByOrderByTickerAsc();

    Optional<WatchlistEntry> findByTicker(String ticker);

    boolean existsByTicker(String ticker);
}
