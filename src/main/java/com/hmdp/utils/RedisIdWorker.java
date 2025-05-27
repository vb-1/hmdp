package com.hmdp.utils;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class RedisIdWorker {
    //序列号的位数
    private static final int BIT_SIZE = 32;
    private static final long BEGIN_TIMESTAMP = 1748131200L;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        //2.1获取当前天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        //2.2自增长
        Long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + "date");
        //位运算
        //3.拼接并返回
        return BEGIN_TIMESTAMP << BIT_SIZE | increment;
    }

}
