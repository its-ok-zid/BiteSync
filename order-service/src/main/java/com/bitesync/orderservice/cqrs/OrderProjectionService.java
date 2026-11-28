package com.bitesync.orderservice.cqrs;

import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.dto.OrderItemResponse;
import com.bitesync.orderservice.event.OrderCreatedEvent;
import com.bitesync.orderservice.event.OrderStatusChangedEvent;
import com.bitesync.orderservice.model.OrderStatus;
import com.bitesync.orderservice.model.OrderSummaryView;
import com.bitesync.orderservice.model.ProcessedEvent;
import com.bitesync.orderservice.model.ProcessedEventId;
import com.bitesync.orderservice.repository.OrderSummaryViewRepository;
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
import java.util.List;
import java.util.Optional;

@Service
public class OrderProjectionService {

    private static final Logger log = LoggerFactory.getLogger(OrderProjectionService.class);

    private final OrderSummaryViewRepository orderSummaryViewRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public OrderProjectionService(
            OrderSummaryViewRepository orderSummaryViewRepository,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper) {
        this.orderSummaryViewRepository = orderSummaryViewRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void projectOrderCreated(OrderCreatedEvent event) {
        ProcessedEventId processedId = new ProcessedEventId(
                event.eventId(),
                OrderConstants.KAFKA_GROUP_PROJECTOR
        );

        if (processedEventRepository.existsById(processedId)) {
            log.info("[CQRS DUPLICATE IGNORED] eventId={} orderId={}", event.eventId(), event.orderId());
            return;
        }

        Instant timestamp = (event.occurredAt() != null) ? event.occurredAt() : Instant.now();

        // Out-of-order guard: never overwrite an already CONFIRMED/CANCELLED view back to PENDING
        Optional<OrderSummaryView> existing = orderSummaryViewRepository.findById(event.orderId());
        if (existing.isPresent()) {
            log.info("[CQRS OUT-OF-ORDER SKIP] View already exists for orderId={} with status={}",
                    event.orderId(), existing.get().getStatus());
            processedEventRepository.save(new ProcessedEvent(processedId, event.eventType(), Instant.now()));
            return;
        }

        List<OrderItemResponse> itemSummaries = event.items().stream()
                .map(i -> new OrderItemResponse(
                        i.itemId(),
                        null,
                        i.quantity(),
                        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                ))
                .toList();

        int totalItemCount = itemSummaries.stream()
                .mapToInt(OrderItemResponse::quantity)
                .sum();

        OrderSummaryView view = new OrderSummaryView();
        view.setOrderId(event.orderId());
        view.setCustomerId(event.customerId());
        view.setRestaurantId(event.restaurantId());
        view.setRestaurantName(null);
        view.setStatus(OrderStatus.PENDING);
        view.setTotalAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        view.setItemCount(totalItemCount);
        view.setItemsJson(serializeItems(itemSummaries));
        view.setCancellationReason(null);
        view.setCreatedAt(timestamp);
        view.setUpdatedAt(timestamp);

        orderSummaryViewRepository.save(view);
        processedEventRepository.save(new ProcessedEvent(processedId, event.eventType(), Instant.now()));

        log.info("[CQRS PROJECTED CREATED] orderId={} status=PENDING itemCount={}",
                event.orderId(), totalItemCount);
    }

    @Transactional
    public void projectOrderStatusChanged(OrderStatusChangedEvent event) {
        ProcessedEventId processedId = new ProcessedEventId(
                event.eventId(),
                OrderConstants.KAFKA_GROUP_PROJECTOR
        );

        if (processedEventRepository.existsById(processedId)) {
            log.info("[CQRS DUPLICATE IGNORED] eventId={} orderId={}", event.eventId(), event.orderId());
            return;
        }

        Instant timestamp = (event.occurredAt() != null) ? event.occurredAt() : Instant.now();

        List<OrderItemResponse> itemSummaries = (event.items() != null ? event.items() : List.<OrderStatusChangedEvent.OrderItemSnapshot>of())
                .stream()
                .map(i -> new OrderItemResponse(
                        i.itemId(),
                        i.itemName(),
                        i.quantity(),
                        i.unitPrice() != null
                                ? i.unitPrice().setScale(2, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                ))
                .toList();

        int totalItemCount = itemSummaries.stream()
                .mapToInt(OrderItemResponse::quantity)
                .sum();

        // Upsert: updates existing view or creates one safely if ORDER_CREATED hasn't arrived yet
        OrderSummaryView view = orderSummaryViewRepository.findById(event.orderId())
                .orElseGet(() -> {
                    OrderSummaryView created = new OrderSummaryView();
                    created.setOrderId(event.orderId());
                    created.setCustomerId(event.customerId());
                    created.setRestaurantId(event.restaurantId());
                    created.setCreatedAt(timestamp);
                    return created;
                });

        view.setRestaurantName(event.restaurantName());
        view.setStatus(event.status());
        view.setTotalAmount(
                event.totalAmount() != null
                        ? event.totalAmount().setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
        );
        view.setItemCount(totalItemCount);
        view.setItemsJson(serializeItems(itemSummaries));
        view.setCancellationReason(event.cancellationReason());
        view.setUpdatedAt(timestamp);

        orderSummaryViewRepository.save(view);
        processedEventRepository.save(new ProcessedEvent(processedId, event.eventType(), Instant.now()));

        log.info("[CQRS PROJECTED {}] orderId={} total={} reason={}",
                event.status(), event.orderId(), view.getTotalAmount(), view.getCancellationReason());
    }

    private String serializeItems(List<OrderItemResponse> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize items_json for order_summary_view", ex);
        }
    }
}