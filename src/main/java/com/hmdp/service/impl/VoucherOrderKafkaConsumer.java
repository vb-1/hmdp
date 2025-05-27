package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class VoucherOrderKafkaConsumer {

    @Autowired
    private IVoucherOrderService voucherOrderService; // 用于调用实际的订单创建逻辑

    @Autowired
    private RedissonClient redissonClient; // 用于分布式锁

    private static final String LOCK_ORDER_KEY = "lock:order:";


    // topics 指定监听的主题，groupId 指定消费者组
    @KafkaListener(topics = "ordertask", groupId = "voucher-order-group")
    public void handleVoucherOrderMessage(ConsumerRecord<String, VoucherOrder> record) {
        log.info("接收到Kafka消息，准备处理订单，key: {}, value: {}", record.key(), record.value());
        VoucherOrder voucherOrder = record.value();

        if (voucherOrder == null || voucherOrder.getUserId() == null || voucherOrder.getVoucherId() == null) {
            log.error("接收到的订单信息不完整: {}", voucherOrder);
            // 可以考虑发送到死信队列 (DLQ)  人工处理
            return;
        }

        // 获取用户，用于分布式锁的key
        Long userId = voucherOrder.getUserId();
        // 创建锁对象（用户ID）
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock(); // 可以设置超时时间

        // 判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，表示该用户已有订单正在处理中，或者之前的请求还未完成
            // 这种情况通常不应该发生，因为Lua脚本层面已经做过一人一单的判断
            // 但作为保险，可以记录日志，或者根据业务决定是否重试 (Kafka有重试机制)
            log.warn("获取订单锁失败，可能重复处理或并发冲突，userId: {}", userId);
            // 根据Kafka的配置，如果这里抛出异常，消息可能会被重新消费
            // throw new RuntimeException("获取订单锁失败，userId: " + userId); // 谨慎使用，可能导致消息无限重试
            return; // 或者直接返回，让消息被ack，避免无限重试
        }

        try {
            // **核心订单创建逻辑**
            // 调用原先在 VoucherOrderHandler 或 seckillVoucher 方法中，
            // 由代理对象调用的 createVoucherOrder 方法的内部实现。
            // 这个方法应该包含：
            // 1. 再次检查订单是否已存在（双重检查，以防万一）。
            // 2. 扣减数据库中的优惠券库存 (seckill_voucher 表的 stock)。
            // 3. 创建订单记录并保存到数据库 (voucher_order 表)。
            // 这个方法需要是事务性的。
            // voucherOrderService.createVoucherOrder(voucherOrder); // 假设有这么一个方法
            // 或者直接调用你实际的创建订单的逻辑，确保它是事务性的。
            // 如果 createVoucherOrder 方法本身就加了 @Transactional，并且 voucherOrderService 是代理对象，那么事务会生效。
            createOrderInTransaction(voucherOrder);

            log.info("订单处理成功, orderId: {}", voucherOrder.getId());
        } catch (Exception e) {
            log.error("Kafka消费者处理订单异常, orderId: {}, error: {}", voucherOrder.getId(), e.getMessage(), e);
            // 异常处理：
            // 1. 记录异常。
            // 2. 根据异常类型决定是否需要重试 (Kafka可以配置重试)。
            // 3. 如果是不可恢复的错误，可以将消息发送到死信队列 (DLQ)。
            // 如果这里抛出异常，Kafka会根据配置进行重试。
            throw e; // 重新抛出，让Kafka进行重试 (需要配置retry)
        } finally {
            // 释放锁
            if (lock.isHeldByCurrentThread()) { // 确保是当前线程持有锁才释放
                lock.unlock();
            }
        }
    }

    /**
     * 将实际的数据库操作封装在一个单独的方法中，并标记为事务性。
     * 这样可以确保 voucherOrderService 是通过代理调用的，事务才会生效。
     * 或者，确保 IVoucherOrderService 接口中的 createVoucherOrder 方法被 @Transactional 注解，
     * 并且这里注入的 voucherOrderService 是Spring的代理对象。
     */
    @Transactional
    public void createOrderInTransaction(VoucherOrder voucherOrder) {
        voucherOrderService.createVoucherOrder(voucherOrder);
    }
}
