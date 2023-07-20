package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
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

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
    //利用缓存空对象解决缓存穿透问题
    //    @Override
//    public Result queryById(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY+id;
//        //1.从redis中查询商铺信息
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //2.判断是否存在
//        if(StrUtil.isNotBlank(shopJson)){
//            //如果这个字符串不为空，说明redis有这个数据
//            //3.如果存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
//        }
//        //在这里需要判断redis里面是否是空值
//        if(shopJson!=null){//不为null的时候为空（这里可能有些难理解，debug一下就可以看出来了，当shopJson为""字符串的时候才会进入这里面）
//            //如果是空
//            //直接返回店铺为空就行了
//            return Result.fail("该店铺不存在");
//        }
//        //4.如果不存在，查询数据库
//        Shop shop = getById(id);
//        //5.如果数据库里面不存在，直接返回错误
//        if(shop==null){
//            //如果数据库中不存在，做把空值存储到redis里面
//            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return Result.fail("该店铺不存在");
//        }
//        //6.如果存在，就写入redis，并且返回
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return Result.ok(shop);
//    }




    //利用互斥锁解决缓存击穿问题
    @Override
    public Result queryById(Long id) {
        Shop shop=queryWithMutex(id);
        return Result.ok(shop);
    }
    //利用互斥锁解决缓存击穿问题
    public Shop queryWithMutex(Long id)  {
        String key = CACHE_SHOP_KEY + id;
        // 1、从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get("key");
        // 2、判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在,直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的值是否是空值
        if (shopJson != null) {
            //返回一个错误信息
            return null;
        }
        // 4.实现缓存重构
        //4.1 获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);//这里设置锁的key为lock:shop+id
            // 4.2 判断否获取成功
            if(!isLock){
                //4.3 失败，则休眠重试
                Thread.sleep(50);
                //这里可能有点难以理解，是递归调用，执行到这里之后会有两个结果，一个是正处于缓存重建，一个是重建完了，可以直接获取缓存了
                return queryWithMutex(id);//进行递归调用
            }
            //4.4 成功，根据id查询数据库
            shop = getById(id);
            // 5.不存在，返回错误
            if(shop == null){
                //将空值写入redis，这里用空值解决了缓存穿透问题
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.写入redis，
            //这里是重建过程
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_NULL_TTL,TimeUnit.MINUTES);

        }catch (Exception e){
            throw new RuntimeException(e);
        }
        finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }
    private boolean tryLock(String key) {
        //这里的value其实设置的是1，因为只是为了实现锁这个功能，并且设置了超时时间，防止出现错误无法释放锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
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
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}
