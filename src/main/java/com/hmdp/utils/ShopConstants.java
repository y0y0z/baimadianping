package com.hmdp.utils;

public class ShopConstants {
    public static final String SHOP_CACHE_PREFIX = "shop:cache:";
    public static final String SHOP_MUTEX_PREFIX = "shop:mutex:";

    /**
     *  商铺信息缓存有效时间
     */
    public static final Long SHOP_CACHE_TTL  = 30L;
    public static final Long SHOP_MUTEX_TTL = 3L;
    public static final Long SHOP_MUTEX_SLEEP_TTL = 50L;
    public static final Long SHOP_LOGICALDEL_TTL = 10L;
    public static final Long SHOP_NULL_TTL = 10L;
}
