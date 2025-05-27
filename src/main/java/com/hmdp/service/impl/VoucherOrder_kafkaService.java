package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IvoucherOrder_kafkaService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;

@Slf4j
@Service
public class VoucherOrder_kafkaService extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IvoucherOrder_kafkaService {
    @Autowired
    private KafkaTemplate<String, VoucherOrder> kafkaTemplate; // 假设VoucherOrder是你想发送的对象类型
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final String KAFKA_TOPIC_VOUCHER_ORDER = "ordertask"; // 定义Kafka主题名称
    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 加载 判断秒杀券库存是否充足 并且 判断用户是否已下单 的Lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    @Override
    public Result seckillvoucher(Long voucherId){

        Long userId = UserHolder.getUser().getId();
        // 1. 执行lua脚本（返回结果是购买资格，1是库存不足，2是重复下单，3为有资格购买）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT, // SECKILL_SCRIPT 需要加载你的 seckill.lua
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(redisIdWorker.nextId("order")) // 假设脚本需要订单ID
        );
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 获取代理对象（用于事务） - 注意：如果lua脚本已经处理了大部分逻辑，这里的事务可能只需要关注将订单信息发送到Kafka
        // 并且确保发送成功。如果发送失败，可能需要回滚lua脚本的操作（如果可能）或者记录失败。

        long orderId = redisIdWorker.nextId("order"); // 如果lua脚本没有返回订单ID，则在这里生成

        // 创建订单信息对象准备发送到Kafka
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId); // 使用生成的orderId

        // 发送消息到Kafka
        try {
            kafkaTemplate.send(KAFKA_TOPIC_VOUCHER_ORDER, String.valueOf(orderId), voucherOrder); // 使用 orderId 作为 key
            log.info("订单信息已发送到Kafka, orderId: {}", orderId);
        } catch (Exception e) {
            log.error("发送订单信息到Kafka失败, orderId: {}, error: {}", orderId, e.getMessage());
            // 这里需要考虑失败处理策略，例如：
            // 1. 记录失败的订单，后续手动处理或重试。
            // 2. 如果lua脚本有回滚机制，尝试回滚。
            // 3. 返回错误给用户。
            return Result.fail("下单请求失败，请稍后再试");
        }

        // 返回订单id (或者一个表示请求已接受的成功消息)
        return Result.ok(orderId);
    }
}
