package com.bitesync.orderservice.repository;

import com.bitesync.orderservice.model.ProcessedEvent;
import com.bitesync.orderservice.model.ProcessedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {
}