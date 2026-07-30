package com.swingscope.repository;

import com.swingscope.domain.journal.TradeJournalEntry;
import com.swingscope.domain.journal.TradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeJournalRepository extends JpaRepository<TradeJournalEntry, Long> {

    List<TradeJournalEntry> findAllByOrderByDatePlannedDescIdDesc();

    List<TradeJournalEntry> findByStatusInOrderByDateClosedDesc(List<TradeStatus> statuses);

    long countByStatus(TradeStatus status);
}
