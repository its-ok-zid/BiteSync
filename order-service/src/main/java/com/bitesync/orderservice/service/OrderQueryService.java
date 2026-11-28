package com.bitesync.orderservice.service;

import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.dto.OrderItemResponse;
import com.bitesync.orderservice.dto.OrderSummaryResponse;
import com.bitesync.orderservice.model.OrderSummaryView;
import com.bitesync.orderservice.repository.OrderSummaryViewRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrderQueryService {

    private static final TypeReference<List<OrderItemResponse>> ITEMS_LIST_TYPE = new TypeReference<>() {};

    private final OrderSummaryViewRepository orderSummaryViewRepository;
    private final ObjectMapper objectMapper;

    public OrderQueryService(
            OrderSummaryViewRepository orderSummaryViewRepository,
            ObjectMapper objectMapper) {
        this.orderSummaryViewRepository = orderSummaryViewRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * CQRS Read Side: Fetches all orders belonging to the authenticated customer
     * directly from the denormalized order_summary_view table (zero JOINs).
     */
    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> getOrdersForCustomer(String customerIdSubject) {
        UUID customerId = parseUuid(customerIdSubject, OrderConstants.MSG_INVALID_CUSTOMER_ID);

        return orderSummaryViewRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * CQRS Read Side: Fetches a single order summary with strict ownership enforcement
     * (owning customer or ADMIN only).
     */
    @Transactional(readOnly = true)
    public OrderSummaryResponse getOrderById(String rawOrderId, String authenticatedUserId, boolean isAdmin) {
        UUID orderId = parseUuid(rawOrderId, OrderConstants.MSG_INVALID_ORDER_ID);

        OrderSummaryView view = orderSummaryViewRepository.findById(orderId)
                .orElseThrow(() -> new BiteSyncException(
                        OrderConstants.MSG_ORDER_NOT_FOUND,
                        OrderConstants.ERROR_ORDER_NOT_FOUND
                ));

        if (!isAdmin && !view.getCustomerId().toString().equals(authenticatedUserId)) {
            throw new AccessDeniedException(OrderConstants.MSG_ORDER_ACCESS_DENIED);
        }

        return toResponse(view);
    }

    private OrderSummaryResponse toResponse(OrderSummaryView view) {
        return new OrderSummaryResponse(
                view.getOrderId(),
                view.getCustomerId(),
                view.getRestaurantId(),
                view.getRestaurantName(),
                view.getStatus(),
                view.getTotalAmount(),
                view.getItemCount(),
                view.getCancellationReason(),
                deserializeItems(view.getItemsJson()),
                view.getCreatedAt(),
                view.getUpdatedAt()
        );
    }

    private UUID parseUuid(String raw, String errorMessage) {
        if (raw == null || raw.isBlank()) {
            throw new BiteSyncException(errorMessage, OrderConstants.ERROR_INVALID_ORDER_REQUEST);
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BiteSyncException(errorMessage, OrderConstants.ERROR_INVALID_ORDER_REQUEST);
        }
    }

    private List<OrderItemResponse> deserializeItems(String itemsJson) {
        try {
            return objectMapper.readValue(itemsJson, ITEMS_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new BiteSyncException(
                    OrderConstants.MSG_DESERIALIZE_ITEMS_ERROR,
                    OrderConstants.ERROR_SERIALIZATION
            );
        }
    }
}