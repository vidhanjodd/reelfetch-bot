package com.reelfetch_bot.repository;

import com.reelfetch_bot.model.DownloadLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DownloadLogRepository extends JpaRepository<DownloadLog, Long> {
}