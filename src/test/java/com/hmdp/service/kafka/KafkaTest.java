package com.hmdp.service.kafka;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class KafkaTest {

    @Autowired
    private SimpleKafkaProducer kafkaProducer;

    @Test
    public void testSendMessage() {
        kafkaProducer.send("test-topic", "测试 Kafka 消息发送！");
    }
}
