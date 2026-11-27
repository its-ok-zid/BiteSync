package com.bitesync.restaurantservice.saga;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.restaurantservice.constants.RestaurantConstants;
import com.bitesync.restaurantservice.event.OrderCreatedEvent;
import com.bitesync.restaurantservice.event.RestaurantOrderDecisionEvent;
import com.bitesync.restaurantservice.model.MenuCategory;
import com.bitesync.restaurantservice.model.MenuItem;
import com.bitesync.restaurantservice.model.ProcessedOrderEvent;
import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;
import com.bitesync.restaurantservice.repository.ProcessedOrderEventRepository;
import com.bitesync.restaurantservice.repository.RestaurantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OrderValidationSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderValidationSagaService.class);
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 5;

    private final RestaurantRepository restaurantRepository;
    private final ProcessedOrderEventRepository processedOrderEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OrderValidationSagaService(
            RestaurantRepository restaurantRepository,
            ProcessedOrderEventRepository processedOrderEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.restaurantRepository = restaurantRepository;
        this.processedOrderEventRepository = processedOrderEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void processOrderCreated(OrderCreatedEvent event) {
        if (processedOrderEventRepository.existsById(event.eventId())) {
            log.info("[SAGA DUPLICATE IGNORED] eventId={} orderId={}", event.eventId(), event.orderId());
            return;
        }

        // Always read directly from MongoDB (never L1/L2 cache) for authoritative checkout pricing & availability
        RestaurantOrderDecisionEvent decision = evaluateOrder(event);

        publishDecision(decision);

        try {
            processedOrderEventRepository.insert(new ProcessedOrderEvent(
                    event.eventId(),
                    event.orderId().toString(),
                    decision.eventType(),
                    Instant.now()
            ));
        } catch (DuplicateKeyException ex) {
            log.info("[SAGA CONCURRENT DUPLICATE] eventId={} already recorded", event.eventId());
        }
    }

    private RestaurantOrderDecisionEvent evaluateOrder(OrderCreatedEvent event) {
        Optional<Restaurant> restaurantOpt = restaurantRepository.findById(event.restaurantId());
        if (restaurantOpt.isEmpty()) {
            return buildRejection(event, null, RestaurantConstants.MSG_SAGA_RESTAURANT_NOT_FOUND);
        }

        Restaurant restaurant = restaurantOpt.get();
        if (restaurant.getStatus() != RestaurantStatus.ACTIVE) {
            return buildRejection(event, restaurant.getName(), RestaurantConstants.MSG_SAGA_RESTAURANT_NOT_ACTIVE);
        }

        Map<String, MenuItem> menuByItemId = indexMenuItems(restaurant);
        List<RestaurantOrderDecisionEvent.PricedOrderItem> pricedItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        for (OrderCreatedEvent.OrderItemPayload requested : event.items()) {
            MenuItem menuItem = menuByItemId.get(requested.itemId());
            if (menuItem == null) {
                return buildRejection(
                        event,
                        restaurant.getName(),
                        RestaurantConstants.MSG_SAGA_ITEM_NOT_FOUND_PREFIX + requested.itemId()
                );
            }
            if (!menuItem.isAvailable()) {
                return buildRejection(
                        event,
                        restaurant.getName(),
                        RestaurantConstants.MSG_SAGA_ITEM_UNAVAILABLE_PREFIX + menuItem.getName()
                );
            }

            BigDecimal unitPrice = menuItem.getPrice().setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTotal = unitPrice
                    .multiply(BigDecimal.valueOf(requested.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            totalAmount = totalAmount.add(lineTotal);

            pricedItems.add(new RestaurantOrderDecisionEvent.PricedOrderItem(
                    menuItem.getItemId(),
                    menuItem.getName(),
                    requested.quantity(),
                    unitPrice
            ));
        }

        return new RestaurantOrderDecisionEvent(
                UUID.randomUUID().toString(),
                BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_ACCEPTED,
                event.orderId(),
                restaurant.getId(),
                restaurant.getName(),
                pricedItems,
                totalAmount,
                null,
                Instant.now()
        );
    }

    private Map<String, MenuItem> indexMenuItems(Restaurant restaurant) {
        Map<String, MenuItem> map = new HashMap<>();
        if (restaurant.getMenuCategories() == null) {
            return map;
        }
        for (MenuCategory category : restaurant.getMenuCategories()) {
            if (category.getItems() == null) {
                continue;
            }
            for (MenuItem item : category.getItems()) {
                if (item.getItemId() != null) {
                    map.put(item.getItemId(), item);
                }
            }
        }
        return map;
    }

    private RestaurantOrderDecisionEvent buildRejection(
            OrderCreatedEvent event,
            String restaurantName,
            String reason) {
        return new RestaurantOrderDecisionEvent(
                UUID.randomUUID().toString(),
                BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_REJECTED,
                event.orderId(),
                event.restaurantId(),
                restaurantName,
                List.of(),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                reason,
                Instant.now()
        );
    }

    private void publishDecision(RestaurantOrderDecisionEvent decision) {
        try {
            String payload = objectMapper.writeValueAsString(decision);
            kafkaTemplate.send(
                    BiteSyncCommonConstants.TOPIC_RESTAURANT_EVENTS,
                    decision.orderId().toString(),
                    payload
            ).get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("[SAGA DECISION PUBLISHED] type={} orderId={} total={} reason={}",
                    decision.eventType(), decision.orderId(), decision.totalAmount(), decision.rejectionReason());
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to publish restaurant saga decision for orderId=" + decision.orderId(), ex);
        }
    }
}