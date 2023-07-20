package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 昊昊
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY+id;
        //1.从redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //如果这个字符串不为空，说明redis有这个数据
            //3.如果存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //在这里需要判断redis里面是否是空值
        if(shopJson!=null){//不为null的时候为空
            //如果是空
            //直接返回店铺为空就行了
            return Result.fail("该店铺不存在");
        }
        //4.如果不存在，查询数据库
        Shop shop = getById(id);
        //5.如果数据库里面不存在，直接返回错误
        if(shop==null){
            //如果数据库中不存在，做把空值存储到redis里面
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("该店铺不存在");
        }
        //6.如果存在，就写入redis，并且返回
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    //为了保证事务，这里添加Transactional注解
    @Transactional
    @Override
    public Result update(Shop shop) {
        //首先进行安全性校验，判断shop是否合法
        //这里通过id进行判断
        Long id = shop.getId();
        if (id==null){
            return Result.fail("您传递的参数不正确，可能是店铺id不能为空");
        }
        //更新数据库（注意是先更新数据库，再删除redis缓存）
        updateById(shop);
        //删除redis缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}
