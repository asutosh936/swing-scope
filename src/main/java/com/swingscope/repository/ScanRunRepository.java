package com.swingscope.repository;

import com.swingscope.domain.scan.ScanRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ScanRunRepository extends JpaRepository<ScanRun, String> {

    List<ScanRun> findAllByOrderByStartedAtDesc();

    @Modifying
    @Query("delete from ScanRun r where r.startedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    long countByStartedAtBefore(Instant cutoff);
}
