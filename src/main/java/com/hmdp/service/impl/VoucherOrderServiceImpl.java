package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.lock.impl.SimpleRedisLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
/*    订单超卖
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result seckillvoucher(Long voucherId) {
        //1.根据提交的优惠卷id，查询优惠卷   根据秒杀卷的表查  也算垂直分表的一种
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始！");
        };
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        //4.判断库存是否充足
        if (voucher.getStock()<1) {
            return Result.fail("库存不足！");
        }
        //5.扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId).update();
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)    //where voucher_id = voucherId and stock = voucher.getStock()   >0
                .gt("stock",0).update();
        if (!success) {
            //扣减失败
            return Result.fail("库存不足");
        }
        //
         //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderid = redisIdWorker.nextId("order");
        voucherOrder.setId(orderid);
        voucherOrder.setVoucherId(voucherId);
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);

        save(voucherOrder);

        return Result.ok(orderid);
    }*/

    /**
     * 抢购秒杀券
     *
     * @param voucherId
     * @return
     */
    /*@Transactional
    @Override
    public Result seckillvoucher(Long voucherId) {
        // 1、查询秒杀券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2、判断秒杀券是否合法
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 秒杀券的开始时间在当前时间之后
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 秒杀券的结束时间在当前时间之前
            return Result.fail("秒杀已结束");
        }
        if (voucher.getStock() < 1) {
            return Result.fail("秒杀券已抢空");
        }
        // 3、创建订单
        Long userId = UserHolder.getUser().getId();
        *//*synchronized (userId.toString().intern()) {
            // 创建代理对象，使用代理对象调用第三方事务方法， 防止事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder( voucherId);
        }*//*
        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:"+userId);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder( voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }*/
    /**
     * 加载 判断秒杀券库存是否充足 并且 判断用户是否已下单 的Lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private BlockingQueue<VoucherOrder> ordertask = new ArrayBlockingQueue<>(1024 * 1024);

    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderTask());
    }

    private class VoucherOrderTask implements Runnable {
        @Override
        public void run() {
            while (true) {
                //1.获取队列中的订单信息
                try {
                    VoucherOrder voucher = ordertask.take();
                    handleVoucherOrder(voucher);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
                //2.创建订单

            }
        }

        private void handleVoucherOrder(VoucherOrder voucher) {
            Long userId = voucher.getUserId();
        //创建锁对象
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean isLock = lock.tryLock();
            if(!isLock) {
            log.error("不允许重复下单");
        }
        try
        {

            proxy.createVoucherOrder(voucher);
        } catch(
        IllegalStateException e)

        {
            throw new RuntimeException(e);
        } finally

        {
            lock.unlock();
        }
    }
}
private IVoucherOrderService proxy;
//lua脚本 阻塞消息队列实现
@Transactional
@Override
public Result seckillvoucher(Long voucherId) {
    Long userId = UserHolder.getUser().getId();
    long orderId = redisIdWorker.nextId("order");
    //1.执行lua脚本
    Long result = null;
    result = stringRedisTemplate.execute(
            SECKILL_SCRIPT,
            Collections.emptyList(),
            voucherId.toString(),
            userId.toString());
    //2.判断结果是否为0
    //2.1 不为0，代表没有下单资格 返回错误信息
    if (result != null && !result.equals(0L)) {
        // result为1表示库存不足，result为2表示用户已下单
        int r = result.intValue();
        return Result.fail(r == 2 ? "不能重复下单" : "库存不足");
    }
    //2.2 为0，代表可以下单，将下单信息交给消息队列
    //下单信息 用户id 订单id  优惠卷id
    VoucherOrder voucherOrder = new VoucherOrder();
    long orderid = redisIdWorker.nextId("order");
    voucherOrder.setId(orderid);
    voucherOrder.setVoucherId(voucherId);
    voucherOrder.setUserId(userId);
    ordertask.add(voucherOrder);
    //获取代理对象
    proxy = (IVoucherOrderService) AopContext.currentProxy();
    //3.返回订单id
    return Result.ok(orderId);
}

/**
 * 创建订单
 */
@Transactional
public void createVoucherOrder(VoucherOrder voucherOrder) {
//        synchronized (userId.toString().intern()) {   //锁释放和事务提交的时间不一致，所以会出现超卖问题。
    // 1、判断当前用户是否是第一单
    Long userId = voucherOrder.getUserId();
    Long voucherId = voucherOrder.getVoucherId();
    long count = query().eq("user_id", userId)
            .eq("voucher_id", voucherId).count();
    if (count >= 1) {
        // 当前用户不是第一单
        log.error("用户已购买");
    }
    // 2、用户是第一单，可以下单，秒杀券库存数量减一
    boolean success = seckillVoucherService.update()
            .setSql("stock = stock - 1")
            .eq("voucher_id", voucherId) //where voucher_id = voucherId and stock = voucher.getStock() >0
            .gt("stock", 0).update();

    if (!success) {
        //扣减失败
        throw new RuntimeException("秒杀券扣减失败");
    }
    // 3、创建对应的订单，并保存到数据库
    boolean flag = save(voucherOrder);
    if (!flag) {
        log.error("创建订单失败");
    }
}

}
