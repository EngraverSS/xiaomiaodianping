package com.xmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.xmdp.dto.Result;
import com.xmdp.entity.Shop;
import com.xmdp.mapper.ShopMapper;
import com.xmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import static com.xmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        // 步骤6可以看出，这里的shop都是以JSON字符串的形式存储进去，
        // 所以这里拿出来也是JSON字符串，也就导致了这段代码中使用JSONUtil类型互转了两次
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        // 工具类StrUtil判断shopJson是否为空
        if (StrUtil.isBlank(shopJson)) {
            // 3.存在，直接返回
            // 使用JSONUtil将shopJson转成java对象返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5.不存在，返回错误
        // shop.null快捷键直接生成if判断
        if (shop == null) {
            return Result.fail("店铺不存在！！");
        }
        // 6.存在，写入redis
        // 使用JSONUtil将shop转回JSON格式，放入value
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
        // 7.返回
        return Result.ok(shop);
    }
}
