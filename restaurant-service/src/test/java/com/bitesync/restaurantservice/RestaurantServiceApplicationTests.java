package com.bitesync.restaurantservice;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Full context load requires live MongoDB/Redis/Eureka — covered by Testcontainers in Phase 5")
class RestaurantServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}