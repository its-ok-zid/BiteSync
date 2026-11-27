package com.bitesync.restaurantservice.repository;

import com.bitesync.restaurantservice.model.ProcessedOrderEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedOrderEventRepository extends MongoRepository<ProcessedOrderEvent, String> {
}