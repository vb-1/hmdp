package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 定义 Redis key
        String cacheKey = "shop:type:list";

        // 1. 从redis查询商铺类型缓存
        String typeListJson = stringRedisTemplate.opsForValue().get(cacheKey);

        // 2. 判断是否存在
        if (StrUtil.isNotBlank(typeListJson)) {
            // 将JSON数组转换为List<ShopType>
            List<ShopType> typeList = JSONUtil.toList(typeListJson, ShopType.class);
            return Result.ok(typeList);
        }

        // 3. 不存在则查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 4. 判断数据库中是否存在
        if (CollectionUtil.isEmpty(typeList)) {
            return Result.fail("分类不存在");
        }

        // 5. 数据库存在则写入redis（设置30分钟过期时间）
        stringRedisTemplate.opsForValue().set(
                cacheKey,
                JSONUtil.toJsonStr(typeList),
                30,
                TimeUnit.MINUTES
        );

        return Result.ok(typeList);
    }


}
