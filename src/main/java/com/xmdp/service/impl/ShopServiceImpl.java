package com.xmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xmdp.dto.Result;
import com.xmdp.entity.Shop;
import com.xmdp.mapper.ShopMapper;
import com.xmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.xmdp.utils.RedisConstants.*;

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

    @Override
    public Result queryById(Long id) {
        // 防止缓存穿透,这里将之前的逻辑封装入queryWithPassThrough(id)
        // 然后在这里调用它
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        // queryWithMutex(id)可能会拿到null，友好一些返回给前端一个Result
        if (shop == null) {
            return Result.fail("店铺不存在！！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    // 创建一个线程池，自己写一个线程会导致经常创建和销毁会导致性能很差
    // 这里等于一个缓存重建的执行器
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期方式解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        // 步骤6可以看出，这里的shop都是以JSON字符串的形式存储进去，
        // 所以这里拿出来也是JSON字符串，也就导致了这段代码中使用JSONUtil类型互转了两次
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        // 工具类StrUtil判断shopJson是否为空
        // 这里查到""空字符串或null返回的都是false
        if (StrUtil.isBlank(shopJson)) {
            // 3.未命中，这里直接返回
            return null;
        }
        // 4.命中，需要把JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 这里的data因为在RedisData中声明的就是Object，因为可能会存其他的数据进入，没有写死是Shop
        // 所以这里可以强转成JSONObject类型（本身是Object），然后供JSONUtil.toBean()使用,
        // 如果是Object的类型toBean()方法没有办法直接使用
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        // 本句判断是指，expireTime是否在now之后，如果true就是未过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否取锁成功
        if (isLock) {

            // 双检---------------------------------------------------------------------------
            // （1）从redis查询商铺缓存
            String shopJson2 = stringRedisTemplate.opsForValue().get(key);
            // （2）（3）不需要判断是否存在，因为没有逻辑会删掉他
            // （4）命中，需要把JSON反序列化为对象
            RedisData redisData2 = JSONUtil.toBean(shopJson2, RedisData.class);
            JSONObject data2 = (JSONObject) redisData2.getData();
            Shop shop2 = JSONUtil.toBean(data2, Shop.class);
            LocalDateTime expireTime2 = redisData.getExpireTime();
            // （5）判断是否过期
            if (expireTime2.isAfter(LocalDateTime.now())) {
                // （6）未过期，意味着有人把逻辑过期的时间更新过了，一定是出了什么差错让本线程闯到了这里
                // 直接把这段没过期的信息（已经更新过了）返回给前端
                return shop2;
            }
            // 双检结束------------------------------------------------------------------------

            // 6.3.获取锁成功，开启独立线程，实现缓存重建
            // submit提交一个任务
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    // 这里直接用我们为缓存预热写的saveShop2Redis，进行缓存重建
                    // 逻辑过期时间为20s，实际应该设置成30min
                    // this是指本对象自己的
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的旧的商铺信息
        return shop;
    }

    // 互斥锁方式解决缓存击穿（包含着返回空对象解决缓存穿透问题）
    // mutex = 互斥锁
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        // 步骤6可以看出，这里的shop都是以JSON字符串的形式存储进去，
        // 所以这里拿出来也是JSON字符串，也就导致了这段代码中使用JSONUtil类型互转了两次
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        // 工具类StrUtil判断shopJson是否为空
        // 这里查到""空字符串或null返回的都是false
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            // 使用JSONUtil将shopJson转成java对象返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值,如果不是null，只能是""空字符串了，也就是我们用来保护数据库的空对象被命中
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.失败，则休眠并重试
                Thread.sleep(50);
                // 重试，直接使用递归，不论多深，不论成功或是失败，都会有return直接干掉方法
                return queryWithMutex(id);
            }

            // 4.4.如果成功，判断缓存是否重建，防止堆积的线程全部请求数据库，真正保证不被击穿
            // 双检（DoubleCheck）---------------------------------------------------
            String shopJson2 = stringRedisTemplate.opsForValue().get(key);
            // (1)判断是否存在
            // 工具类StrUtil判断shopJson是否为空
            // 这里查到""空字符串或null返回的都是false
            if (StrUtil.isNotBlank(shopJson2)) {
                // (2).存在，直接返回
                // 使用JSONUtil将shopJson转成java对象返回
                return JSONUtil.toBean(shopJson2, Shop.class);
            }
            // 判断命中的是否是空值,如果不是null，只能是""空字符串了，也就是我们用来保护数据库的空对象被命中
            if (shopJson2 != null) {
                // 返回一个错误信息
                return null;
            }
            // 双检结束-------------------------------------------------------------------

            // 4.5.双检结束，依然没有缓存，根据id查询数据库（抢到了锁，意味着缓存消失，需要重建缓存）
            shop = getById(id);
            // 模拟重建的延时，因为我们现在redis和mysql均在本地
            Thread.sleep(200);
            // 5.不存在，返回错误
            // shop.null快捷键直接生成if判断
            if (shop == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue()
                        .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            // 使用JSONUtil将shop转回JSON格式，放入value
            stringRedisTemplate.opsForValue()
                    .set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }
        // 8.返回
        return shop;
    }

    // 封装防止缓存穿透的查询方法
    // 之后只需要调用这个方法返回shop就可以了
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        // 步骤6可以看出，这里的shop都是以JSON字符串的形式存储进去，
        // 所以这里拿出来也是JSON字符串，也就导致了这段代码中使用JSONUtil类型互转了两次
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        // 工具类StrUtil判断shopJson是否为空
        // 这里查到""空字符串或null返回的都是false
        if (StrUtil.isNotBlank(shopJson)) {
            // 3.存在，直接返回
            // 使用JSONUtil将shopJson转成java对象返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值,如果不是null，只能是""空字符串了，也就是我们用来保护数据库的空对象被命中
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }
        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5.不存在，返回错误
        // shop.null快捷键直接生成if判断
        if (shop == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue()
                    .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        // 使用JSONUtil将shop转回JSON格式，放入value
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7.返回
        return shop;
    }

    private boolean tryLock(String key) {
        // setIfAbsent()就是setnx，IfAbsent = 如果存在
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 为了避免拆箱带来的空指针，使用hutool包中的BooleanUtil帮助我们判断其中的true or false
        // 拆箱是指将包装类对象中的值提取出来，转换为相应的基本数据类型
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.查询店铺数据
        // getById()能够直接被使用是因为继承了ServiceImpl<ShopMapper, Shop> 而又实现了implements IService<T>
        // 也就是mybatis提供的方法
        Shop shop = getById(id);
        Thread.sleep(200); // 模拟重建缓存的时间，因为我们是在本地操作，非常快
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        // 写入时间，当前时间 + 从形参列表中传入的expireSeconds
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis
        // 使用JSONUtil将redisData从RedisData转为JSON格式
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    // 主动更新策略
    // 新增先更新数据库，再删除缓存
    @Override
    // 这里因为是单体系统可以用事务保持原子性
    // 如果是分布式系统，例如更新完数据库，需要用到MQ去通知另一个系统去完成对缓存的删除操作
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！！");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }


}
