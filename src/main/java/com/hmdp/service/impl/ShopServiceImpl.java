package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.ShopConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
 public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Qualifier("redisTemplate")
    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public Result queryById(Long id) {
//        String cacheKey = ShopConstants.SHOP_CACHE_PREFIX + id;
//
//        String cache = stringRedisTemplate.opsForValue().get(cacheKey);
//
//        if (StrUtil.isNotBlank(cache)) {
//            Shop shop = JSONUtil.toBean(cache, Shop.class);
//            return Result.ok(shop);
//        }
//
//        Shop shop = getById(id);
//
//        if (shop == null) {
//            return Result.fail("查询的商铺不存在");
//        }
//
//        stringRedisTemplate.opsForValue().set(cacheKey , JSONUtil.toJsonStr(shop) , ShopConstants.SHOP_CACHE_TTL , TimeUnit.MINUTES);
//
//        return Result.ok(shop);
        Shop shop = queryWithPassThrough(id);

        if(shop == null){
            return Result.fail("您查询的商铺不存在!");
        }

        return Result.ok(shop);
    }

    /**
     * 更新商铺
     * @param shop 商铺实体
     * @return 执行结果
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();

        if (id == null) {
            return Result.fail("商铺Id不存在!");
        }

        updateById(shop);

        stringRedisTemplate.delete(ShopConstants.SHOP_CACHE_PREFIX + id);

        return Result.ok();
    }

    /**
     * 通过设置特殊value处理缓存穿透的情况
     * @param id
     * @return  
     */
    private Shop queryWithPassThrough(Long id){
        String cacheKey = getShopCacheKey(id);

        String cache = stringRedisTemplate.opsForValue().get(cacheKey);

        if (StrUtil.isNotBlank(cache)) {
            return JSONUtil.toBean(cache, Shop.class);
        }
        if (cache != null){
            return null;
        }

        Shop shop = getById(id);

        if (shop == null) {
            // 设置特殊value
            stringRedisTemplate.opsForValue().set(cacheKey , "" , ShopConstants.SHOP_NULL_TTL , TimeUnit.SECONDS);
            return null;
        }

        stringRedisTemplate.opsForValue().set(cacheKey , JSONUtil.toJsonStr(shop) , ShopConstants.SHOP_CACHE_TTL , TimeUnit.MINUTES);

        return shop;
    }

    private static final ExecutorService LOGICALDEL_THREADPOOL = Executors.newFixedThreadPool(5);

    /**
     * 逻辑删除解决击穿问题
     * @param id 商铺Id
     * @return
     */
    private Shop queryWithLogicalDel(Long id){
        String cacheKey = getShopCacheKey(id);

        String json = stringRedisTemplate.opsForValue().get(cacheKey);

        if(StrUtil.isBlank(json)){
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = BeanUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getLogicalExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }

        String lockKey = ShopConstants.SHOP_MUTEX_PREFIX + id;

        if (tryLock(lockKey)) {
            // double check
            String secondJson = stringRedisTemplate.opsForValue().get(cacheKey);
            RedisData secondRedisData = JSONUtil.toBean(secondJson, RedisData.class);
            LocalDateTime logicalExpireTime = secondRedisData.getLogicalExpireTime();

            if(logicalExpireTime.isAfter(LocalDateTime.now())){
                return JSONUtil.toBean((JSONObject) secondRedisData.getData() , Shop.class);
            }

            // 开启一个线程去处理缓存重建
            LOGICALDEL_THREADPOOL.execute(()->{
                // 重建缓存
                try {
                    Shop rebuildShop = getById(id);

                    RedisData rebuildRedisData = new RedisData();
                    rebuildRedisData.setLogicalExpireTime(LocalDateTime.now().plusSeconds(ShopConstants.SHOP_LOGICALDEL_TTL));
                    rebuildRedisData.setData(rebuildShop);
                    stringRedisTemplate.opsForValue().set(cacheKey , JSONUtil.toJsonStr(rebuildRedisData));

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }

        return shop;
    }

    /**
     * 互斥锁解决缓存击穿(hot key)
     * @param id 商铺id
     * @return
     */
    private Shop queryWithMutex(Long id){
        String cacheKey = getShopCacheKey(id);

        String cache = stringRedisTemplate.opsForValue().get(cacheKey);

        if (StrUtil.isNotBlank(cache)) {
            return JSONUtil.toBean(cache, Shop.class);
        }
        if (cache != null){
            return null;
        }

        Shop shop = null;
        String lockKey = ShopConstants.SHOP_MUTEX_PREFIX + id;
        try {
            if (!tryLock(lockKey)) {
                Thread.sleep(ShopConstants.SHOP_MUTEX_SLEEP_TTL);
                return queryWithMutex(id);
            }

            // double check
            cache = stringRedisTemplate.opsForValue().get(cacheKey);

            if (cache != null) {
                return JSONUtil.toBean(cache , Shop.class);
            }

            // 重建缓存
            shop = getById(id);

            if (shop == null) {
                // 设置特殊value
                stringRedisTemplate.opsForValue().set(cacheKey , "" , ShopConstants.SHOP_NULL_TTL , TimeUnit.SECONDS);
                return null;
            }

            stringRedisTemplate.opsForValue().set(cacheKey , JSONUtil.toJsonStr(shop) , ShopConstants.SHOP_CACHE_TTL , TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }

        return shop;
    }

    private String getShopCacheKey(Long id){
        return ShopConstants.SHOP_CACHE_PREFIX + id;
    }

    private Boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", ShopConstants.SHOP_MUTEX_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
