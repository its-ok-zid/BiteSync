package com.bitesync.orderservice.repository;

import com.bitesync.orderservice.model.OrderSummaryView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderSummaryViewRepository extends JpaRepository<OrderSummaryView, UUID> {

    List<OrderSummaryView> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
}