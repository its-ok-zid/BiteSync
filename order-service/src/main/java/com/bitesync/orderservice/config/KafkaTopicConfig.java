package com.bitesync.orderservice.config;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String ORDER_EVENTS_TOPIC = BiteSyncCommonConstants.TOPIC_ORDER_EVENTS;
    public static final String RESTAURANT_EVENTS_TOPIC = BiteSyncCommonConstants.TOPIC_RESTAURANT_EVENTS;

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(ORDER_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic restaurantEventsTopic() {
        return TopicBuilder.name(RESTAURANT_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}