package com.bitesync.orderservice.service;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.orderservice.config.KafkaTopicConfig;
import com.bitesync.orderservice.dto.*;
import com.bitesync.orderservice.event.OrderCreatedEvent;
import com.bitesync.orderservice.model.*;
import com.bitesync.orderservice.repository.IdempotencyRecordRepository;
import com.bitesync.orderservice.repository.OrderRepository;
import com.bitesync.orderservice.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class OrderCommandService {

    private static final Pattern MONGO_OBJECT_ID_PATTERN = Pattern.compile("^[a-fA-F0-9]{24}$");
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    public OrderCommandService(
            OrderRepository orderRepository,
            OutboxEventRepository outboxEventRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * ACID Command Boundary:
     * 1. Validates Idempotency-Key, customer UUID, restaurant ObjectId, and unique itemIds.
     * 2. Checks idempotency_records -> replays exact response on matching retry or rejects on payload mismatch.
     * 3. Atomically writes Order + OrderItems + OutboxEvent (ORDER_CREATED) + IdempotencyRecord in one DB commit.
     */
    @Transactional
    public PlaceOrderResult placeOrder(PlaceOrderRequest request, String customerIdSubject, String idempotencyKey) {
        String cleanKey = validateIdempotencyKey(idempotencyKey);
        UUID customerId = parseCustomerUuid(customerIdSubject);
        String cleanRestaurantId = validateRestaurantId(request.restaurantId());
        List<OrderItemRequest> normalizedItems = validateAndNormalizeItems(request.items());

        String requestHash = computeRequestHash(cleanRestaurantId, normalizedItems);

        // 1. Check for existing idempotency record
        Optional<IdempotencyRecord> existingOpt =
                idempotencyRecordRepository.findByIdempotencyKeyAndCustomerId(cleanKey, customerId);

        if (existingOpt.isPresent()) {
            IdempotencyRecord existing = existingOpt.get();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new BiteSyncException(
                        "Idempotency-Key already used with a different request payload",
                        "ORDER_003"
                );
            }
            OrderResponse replayed = deserializeOrderResponse(existing.getResponseBody());
            return new PlaceOrderResult(replayed, existing.getHttpStatus(), true);
        }

        // 2. Build and persist Order + OrderItems (status = PENDING; authoritative prices filled by Saga)
        Instant now = Instant.now();
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setCustomerId(customerId);
        order.setRestaurantId(cleanRestaurantId);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(BigDecimal.ZERO.setScale(2));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);

        for (OrderItemRequest itemReq : normalizedItems) {
            OrderItem item = new OrderItem();
            item.setId(UUID.randomUUID());
            item.setItemId(itemReq.itemId());
            item.setQuantity(itemReq.quantity());
            item.setUnitPrice(BigDecimal.ZERO.setScale(2));
            order.addItem(item);
        }

        Order savedOrder = orderRepository.save(order);
        OrderResponse orderResponse = OrderResponse.from(savedOrder);

        // 3. Persist OutboxEvent (ORDER_CREATED) in the exact same PostgreSQL transaction
        UUID eventId = UUID.randomUUID();
        OrderCreatedEvent eventPayload = new OrderCreatedEvent(
                eventId.toString(),
                "ORDER_CREATED",
                savedOrder.getId(),
                savedOrder.getCustomerId(),
                savedOrder.getRestaurantId(),
                savedOrder.getItems().stream()
                        .map(i -> new OrderCreatedEvent.OrderItemPayload(i.getItemId(), i.getQuantity()))
                        .toList(),
                now
        );

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(eventId);
        outboxEvent.setAggregateType("ORDER");
        outboxEvent.setAggregateId(savedOrder.getId().toString());
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setTopic(KafkaTopicConfig.ORDER_EVENTS_TOPIC);
        outboxEvent.setPayload(serializeToJson(eventPayload));
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setRetryCount(0);
        outboxEvent.setCreatedAt(now);
        outboxEventRepository.save(outboxEvent);

        // 4. Persist IdempotencyRecord atomically with the order & outbox event
        IdempotencyRecord idempotencyRecord = new IdempotencyRecord();
        idempotencyRecord.setId(UUID.randomUUID());
        idempotencyRecord.setIdempotencyKey(cleanKey);
        idempotencyRecord.setCustomerId(customerId);
        idempotencyRecord.setRequestHash(requestHash);
        idempotencyRecord.setOrderId(savedOrder.getId());
        idempotencyRecord.setHttpStatus(HttpStatus.CREATED.value());
        idempotencyRecord.setResponseBody(serializeToJson(orderResponse));
        idempotencyRecord.setCreatedAt(now);
        idempotencyRecordRepository.save(idempotencyRecord);

        return new PlaceOrderResult(orderResponse, HttpStatus.CREATED.value(), false);
    }

    private String validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BiteSyncException("Idempotency-Key header is required", "ORDER_002");
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BiteSyncException("Idempotency-Key cannot exceed 128 characters", "ORDER_002");
        }
        return trimmed;
    }

    private UUID parseCustomerUuid(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (Exception ex) {
            throw new BiteSyncException("Invalid authenticated customer ID", "ORDER_002");
        }
    }

    private String validateRestaurantId(String restaurantId) {
        String trimmed = (restaurantId != null) ? restaurantId.trim() : "";
        if (!MONGO_OBJECT_ID_PATTERN.matcher(trimmed).matches()) {
            throw new BiteSyncException("Invalid restaurant ID format", "ORDER_002");
        }
        return trimmed;
    }

    private List<OrderItemRequest> validateAndNormalizeItems(List<OrderItemRequest> rawItems) {
        Set<String> seenItemIds = new HashSet<>();
        List<OrderItemRequest> normalized = new ArrayList<>();

        for (OrderItemRequest item : rawItems) {
            String cleanItemId = item.itemId().trim();
            if (!seenItemIds.add(cleanItemId)) {
                throw new BiteSyncException("Duplicate itemId in order request: " + cleanItemId, "ORDER_002");
            }
            normalized.add(new OrderItemRequest(cleanItemId, item.quantity()));
        }

        // Canonical sort by itemId so identical carts hash identically regardless of JSON array order
        normalized.sort(Comparator.comparing(OrderItemRequest::itemId));
        return normalized;
    }

    private String computeRequestHash(String restaurantId, List<OrderItemRequest> canonicalItems) {
        String canonicalJson = serializeToJson(new PlaceOrderRequest(restaurantId, canonicalItems));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new BiteSyncException("Failed to hash order request", "ORDER_004");
        }
    }

    private String serializeToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            throw new BiteSyncException("Failed to serialize order payload", "ORDER_004");
        }
    }

    private OrderResponse deserializeOrderResponse(String json) {
        try {
            return objectMapper.readValue(json, OrderResponse.class);
        } catch (JsonProcessingException ex) {
            throw new BiteSyncException("Failed to deserialize saved idempotent response", "ORDER_004");
        }
    }
}