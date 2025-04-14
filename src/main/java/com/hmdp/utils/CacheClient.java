package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService LOGICALDELETE_THREADPOOL = Executors.newFixedThreadPool(10);

    /**
     * 非逻辑删除模式添加缓存
     */
    public void set(String key, Object value, Long expire, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key , JSONUtil.toJsonStr(value), expire , unit);
    }

    /**
     * 逻辑删除模式的添加缓存
     * @param key 缓存的key
     * @param value 实际缓存的对象
     * @param logicalExpire 缓存的逻辑过期时间
     * @param unit 过期时间的时间单位
     */
    public void setWithLogicalExpire(String key,Object value, Long logicalExpire,TimeUnit unit){
        RedisData redisData = new RedisData();

        redisData.setData(value);
        redisData.setLogicalExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(logicalExpire)));

        stringRedisTemplate.opsForValue().set(key , JSONUtil.toJsonStr(redisData));
    }

    /**
     * 根据id查询(特殊值处理缓存穿透)
     * @return 查询结果
     * @param <R> 查询结果的类型
     */
    public <R , ID> R queryWithPassThrough(
            String cachePrefix , ID id , Class<R> targetType , Function<ID , R> dbFallback, Long valueTTL,TimeUnit unit){
        String cacheKey = cachePrefix + id;

        String jsonCache = stringRedisTemplate.opsForValue().get(cacheKey);

        // 缓存命中
        if(StrUtil.isNotBlank(jsonCache)){
            return JSONUtil.toBean(jsonCache, targetType);
        }
        if(jsonCache != null){
            return null;
        }

        // rebuild cache
        R r = dbFallback.apply(id);

        if (r == null) {
            set(cacheKey , "" , RedisConstants.CACHE_NULL_TTL , TimeUnit.MINUTES);
            //stringRedisTemplate.opsForValue().set(cacheKey , "" , RedisConstants.CACHE_NULL_TTL , TimeUnit.MINUTES);
            return null;
        }

        set(cacheKey , r , valueTTL , unit);
        //stringRedisTemplate.opsForValue().set(cacheKey , JSONUtil.toJsonStr(r) , valueTTL , unit);

        return r;
    }

    public <R , ID> R queryWithLogicalDel(
            String cachePrefix, String keyPrefix, ID id, Class<R> targetType, Function<ID , R> dbFallback , Long valueTTL , TimeUnit unit){
        String cacheKey = cachePrefix + id;

        String jsonCache = stringRedisTemplate.opsForValue().get(cacheKey);

        if (StrUtil.isBlank(jsonCache)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(jsonCache, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), targetType);
        LocalDateTime expireTime = redisData.getLogicalExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        // rebuild cache
        String lockKey = keyPrefix + id;

        if(tryLock(lockKey)){
            // double check
            String secondCacheJson = stringRedisTemplate.opsForValue().get(cacheKey);
            RedisData secondRedisData = JSONUtil.toBean(secondCacheJson, RedisData.class);

            if(secondRedisData.getLogicalExpireTime().isAfter(LocalDateTime.now())){
                return JSONUtil.toBean((JSONObject) secondRedisData.getData() , targetType);
            }

            // query mysql and rebuild cache with a new thread
            LOGICALDELETE_THREADPOOL.execute(() ->{
                try {
                    R rebuildData = dbFallback.apply(id);

                    this.setWithLogicalExpire(cacheKey , rebuildData , valueTTL , unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });

        }

        return r;
    }

    private <R , ID> R queryWithMutex(
            String cachePrefix, String lockPrefix, ID id,Class<R> targetType,Function<ID , R> dbFallback,Long cacheTTl , TimeUnit unit){
        String cacheKey = cachePrefix + id;

        String jsonCache = stringRedisTemplate.opsForValue().get(cacheKey);

        if (StrUtil.isNotBlank(jsonCache)) {
            return JSONUtil.toBean(jsonCache , targetType);
        }
        if (jsonCache != null){
            return null;
        }

        String lockKey = lockPrefix + id;
        R r = null;
        try {
            // fail to get mutex lock
            if(!tryLock(lockKey)){
                Thread.sleep(50L);
                return queryWithMutex(cachePrefix , lockPrefix , id , targetType , dbFallback , cacheTTl , unit);
            }

            // get lock successfully
            // TODO Double-check
            String secondJsonCache = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(secondJsonCache)) {
                return JSONUtil.toBean(secondJsonCache , targetType);
            }

            r = dbFallback.apply(id);

            if (r == null) {
                set(cacheKey , ""  , RedisConstants.CACHE_NULL_TTL , TimeUnit.MINUTES);
                return null;
            }

            set(cacheKey , r , cacheTTl , unit);

        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            unLock(lockKey);
        }

        return r;
    }

    private boolean tryLock(String lockKey){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String lockKey){
        stringRedisTemplate.delete(lockKey);
    }
}
