package com.bitesync.orderservice.saga;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.orderservice.config.KafkaTopicConfig;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.event.OrderStatusChangedEvent;
import com.bitesync.orderservice.event.RestaurantOrderDecisionEvent;
import com.bitesync.orderservice.model.Order;
import com.bitesync.orderservice.model.OrderItem;
import com.bitesync.orderservice.model.OrderStatus;
import com.bitesync.orderservice.model.OutboxEvent;
import com.bitesync.orderservice.model.OutboxStatus;
import com.bitesync.orderservice.model.ProcessedEvent;
import com.bitesync.orderservice.model.ProcessedEventId;
import com.bitesync.orderservice.repository.OrderRepository;
import com.bitesync.orderservice.repository.OutboxEventRepository;
import com.bitesync.orderservice.repository.ProcessedEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public OrderSagaService(
            OrderRepository orderRepository,
            OutboxEventRepository outboxEventRepository,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handleRestaurantDecision(RestaurantOrderDecisionEvent decision) {
        ProcessedEventId processedId = new ProcessedEventId(
                decision.eventId(),
                OrderConstants.KAFKA_GROUP_SAGA
        );

        if (processedEventRepository.existsById(processedId)) {
            log.info("[ORDER SAGA DUPLICATE IGNORED] eventId={} orderId={}",
                    decision.eventId(), decision.orderId());
            return;
        }

        Optional<Order> orderOpt = orderRepository.findWithItemsById(decision.orderId());
        if (orderOpt.isEmpty()) {
            log.warn("[ORDER SAGA WARN] Order not found for decision eventId={} orderId={}",
                    decision.eventId(), decision.orderId());
            return;
        }

        Order order = orderOpt.get();
        Instant now = Instant.now();

        // State machine guard: only PENDING orders may transition via initial restaurant validation
        if (order.getStatus() != OrderStatus.PENDING) {
            log.info("[ORDER SAGA SKIPPED] Order {} already in terminal/advanced status {}",
                    order.getId(), order.getStatus());
            processedEventRepository.save(new ProcessedEvent(processedId, decision.eventType(), now));
            return;
        }

        String nextOutboxEventType;
        if (BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_ACCEPTED.equals(decision.eventType())) {
            applyAcceptance(order, decision);
            nextOutboxEventType = BiteSyncCommonConstants.EVENT_ORDER_CONFIRMED;
            log.info("[ORDER SAGA CONFIRMED] orderId={} restaurant='{}' total={}",
                    order.getId(), order.getRestaurantName(), order.getTotalAmount());
        } else if (BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_REJECTED.equals(decision.eventType())) {
            applyCompensatingCancellation(order, decision);
            nextOutboxEventType = BiteSyncCommonConstants.EVENT_ORDER_CANCELLED;
            log.info("[ORDER SAGA CANCELLED] orderId={} reason='{}'",
                    order.getId(), order.getCancellationReason());
        } else {
            log.warn("[ORDER SAGA WARN] Unknown decision eventType={}", decision.eventType());
            return;
        }

        Order savedOrder = orderRepository.save(order);
        persistLifecycleOutboxEvent(savedOrder, nextOutboxEventType, now);
        processedEventRepository.save(new ProcessedEvent(processedId, decision.eventType(), now));
    }

    private void applyAcceptance(Order order, RestaurantOrderDecisionEvent decision) {
        order.setStatus(OrderStatus.CONFIRMED);
        order.setRestaurantName(decision.restaurantName());
        order.setTotalAmount(
                decision.totalAmount() != null
                        ? decision.totalAmount().setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
        );
        order.setCancellationReason(null);

        Map<String, RestaurantOrderDecisionEvent.PricedOrderItem> pricedById =
                decision.pricedItems().stream()
                        .collect(Collectors.toMap(
                                RestaurantOrderDecisionEvent.PricedOrderItem::itemId,
                                Function.identity(),
                                (a, b) -> a
                        ));

        for (OrderItem item : order.getItems()) {
            RestaurantOrderDecisionEvent.PricedOrderItem priced = pricedById.get(item.getItemId());
            if (priced != null) {
                item.setItemName(priced.itemName());
                item.setUnitPrice(priced.unitPrice().setScale(2, RoundingMode.HALF_UP));
            }
        }
    }

    private void applyCompensatingCancellation(Order order, RestaurantOrderDecisionEvent decision) {
        order.setStatus(OrderStatus.CANCELLED);
        if (decision.restaurantName() != null) {
            order.setRestaurantName(decision.restaurantName());
        }
        order.setCancellationReason(decision.rejectionReason());
    }

    private void persistLifecycleOutboxEvent(Order order, String eventType, Instant now) {
        UUID outboxEventId = UUID.randomUUID();
        OrderStatusChangedEvent statusEvent = new OrderStatusChangedEvent(
                outboxEventId.toString(),
                eventType,
                order.getId(),
                order.getCustomerId(),
                order.getRestaurantId(),
                order.getRestaurantName(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCancellationReason(),
                order.getItems().stream()
                        .map(i -> new OrderStatusChangedEvent.OrderItemSnapshot(
                                i.getItemId(),
                                i.getItemName(),
                                i.getQuantity(),
                                i.getUnitPrice()
                        ))
                        .toList(),
                now
        );

        OutboxEvent outbox = new OutboxEvent();
        outbox.setId(outboxEventId);
        outbox.setAggregateType(OrderConstants.AGGREGATE_TYPE_ORDER);
        outbox.setAggregateId(order.getId().toString());
        outbox.setEventType(eventType);
        outbox.setTopic(KafkaTopicConfig.ORDER_EVENTS_TOPIC);
        outbox.setPayload(serialize(statusEvent));
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outboxEventRepository.save(outbox);
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize OrderStatusChangedEvent", ex);
        }
    }
}