package com.bitesync.restaurantservice.repository;

import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RestaurantRepository extends MongoRepository<Restaurant, String>, RestaurantCustomRepository {

    // Standard offset pagination (kept alongside cursor pagination for admin/small lists)
    Page<Restaurant> findByCityKeyAndStatus(String cityKey, RestaurantStatus status, Pageable pageable);

    Page<Restaurant> findByStatus(RestaurantStatus status, Pageable pageable);

    // Owner dashboard lookup (backed by idx_owner_status)
    List<Restaurant> findByOwnerId(String ownerId);
}