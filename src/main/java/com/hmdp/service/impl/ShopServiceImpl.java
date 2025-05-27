package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.cache.CacheManager;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /*@Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //3.不存在去查数据库  //mybatis plus 代码
        Shop shop = getById(id);
        //4.再判断数据库中是否存在
        if(shop == null){
            return Result.fail("数据不存在");
        }
        //5数据库存在返回前端并写入redis；
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);

    }*/

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
//        Shop shop = cacheC(id);
        //使用封装方法
//        Shop shop = cacheClient
//                .handleCachePenetration(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
        //互斥锁解决缓存击穿
//        Shop shop = queryMuted(id);
        //逻辑过期解决缓存击穿问题
//        Shop shop = querWithLogicalExpire(id);
        Shop shop = cacheClient.handleCacheBreakdown(CACHE_SHOP_KEY,id, Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);
    //解决缓存击穿，逻辑过期
    public Shop querWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在  isNotBlank（）判断是否是有效的非空字符串。1.不是 null。2，不是空字符串 ""3.不是纯空白字符（如 " "）
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        //3.判断是否命中空值
        if ("NULL".equals(shopJson)) {
            return null;
        }
       //4.命中，需要先把json反序列化为对象
        RedisData redisdata = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop =JSONUtil.toBean((JSONObject) redisdata.getData(),Shop.class) ;
        LocalDateTime expireTime = redisdata.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期
            return shop;
        }
        //5.2 已过期，需要缓存重建
        //6.重建缓存
        //6.1获得互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if(isLock){
            //成功，开启独立线程，实现缓存重建
            executorService.submit(()->{
                try {
                    //重建缓存
                    this.saveShoptoRedis(id,20L);
                    //
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
       //返回过期的商铺信息
        return shop;
    }
    //解决缓存穿透  使用缓存空值
    public Shop queryPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在  isNotBlank（）判断是否是有效的非空字符串。1.不是 null。2，不是空字符串 ""3.不是纯空白字符（如 " "）
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //3.判断是否命中空值
        if ("NULL".equals(shopJson)) {
            return null;
        }
        //" " 表示的是之前已经查询到了店铺信息不存在，然后以" "形式缓存了，然后我们现在命中了" " ,就直接返回店铺信息不存在。
        //null表示当前查询数据没有缓存，且之前没有查询过，所以店铺信息是否存在我们目前不知道！需要再去查询数据库
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.再判断数据库中是否存在，不存在将空值写入redis
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "NULL", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.数据库存在返回前端并写入redis；
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;

    }

    //实现缓存击穿，互斥锁
    public Shop queryMuted(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在  isNotBlank（）判断是否是有效的非空字符串。1.不是 null。2，不是空字符串 ""3.不是纯空白字符（如 " "）
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //3.判断是否命中空值
        if ("NULL".equals(shopJson)) {
            return null;
        }
        //4.不存在，根据id查询数据库
        //实现缓存重建1.获取互斥锁 2.判断是否成功 3.失败，则休眠重试
        String lockKey = "lock:shop" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                Thread.sleep(50);
                return queryMuted(id);
            }
            //成功获得锁，根据id查数据库
            shop = getById(id);
            Thread.sleep(200); //模拟重建延时
            //5.再判断数据库中是否存在，不存在将空值写入redis
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "NULL", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.数据库存在返回前端并写入redis；
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unlock(lockKey);
            return shop;
        }


    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
    public void saveShoptoRedis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
