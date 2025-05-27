package com.hmdp.service.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SimpleKafkaConsumer {

    @KafkaListener(topics = "test-topic", groupId = "crm-user-service")
    public void consume(ConsumerRecord<String, String> record) {
        System.out.println("收到 Kafka 消息：key=" + record.key() + ", value=" + record.value());
    }
}
