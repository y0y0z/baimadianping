package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;


import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final long BEGIN_TIMESTAMP = 1704067200L;

    private static final int SEQUENCE_BITS = 32;

    /**
     * 生成业务唯一ID
     * 1位符号位+31位时间戳+32位序列号
     * @param bizPrefix 业务前缀
     * @return 最新的一位Id
     */
    public long getId(String bizPrefix){
        // 31位时间戳
        LocalDateTime now = LocalDateTime.now();
        long epochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = epochSecond - BEGIN_TIMESTAMP;

        // 32位序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long seq = stringRedisTemplate.opsForValue().increment("incr:" + bizPrefix + ":" + date);

        return timeStamp << SEQUENCE_BITS | seq;
    }
}
