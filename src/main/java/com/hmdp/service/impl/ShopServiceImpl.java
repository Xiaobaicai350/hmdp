package com.hmdp.service.impl;

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
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
        //利用互斥锁解决缓存击穿问题
//        Shop shop=queryWithMutex(id);
        //利用逻辑过期解决缓存击穿问题
        Shop shop = queryWithLogicalExpire(id);
        if(shop==null){
            Result.fail("店铺不存在");
        }
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




    //初始化线程池，用于新建线程用于重建数据
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //注意，在使用这个的时候，需要先往redis里面增加数据，之后才能进行逻辑过期的更新，，因为这些热点key的话，是需要提前加入到redis里面的
    public Shop queryWithLogicalExpire( Long id ) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存，注意这里得到的是逻辑过期数据
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //这里说明之前就没有这条缓存数据，我们知道，热点key需要提前的时候存入redis中
            //如果这里不存在，说明这个就不是热点key，我们也没有查询的必要了
            // 3.不存在，返回空
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
//        redisData.getData()这里得到的是一个Object对象，JSONUtil没有办法帮我们转换成Shop对象，需要我们手动转一下
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //得到过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            //获取锁成功，进行重建缓存
            CACHE_REBUILD_EXECUTOR.submit( ()->{
                try{
                    //重建缓存,并且设置逻辑过期，其实就是续了一段时间，并且更新了缓存里面的数据
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return shop;
    }


        /**
         * 重建redis中的数据
         * @param id 需要重建的店铺id
         * @param expireSeconds 设置的逻辑过期时间
         */
    public void saveShop2Redis(Long id ,Long expireSeconds){
        //查询重建的店铺数据
        Shop shop = getById(id);
        //封装带有逻辑过期时间的类
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        //设置过期时间为 当前时间加上传入的过期秒数
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //把带有逻辑过期的数据存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
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
