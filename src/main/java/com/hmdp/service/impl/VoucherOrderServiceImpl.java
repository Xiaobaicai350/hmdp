package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    RedisIdWorker redisIdWorker;
    @Resource
    ISeckillVoucherService seckillVoucherService;
    //抢购秒杀券方法，需要操作的表有订单表，优惠券表
    @Override
    public Result seckillVoucher(Long voucherId) {
        //得到秒杀券的信息，包括开始时间结束时间，库存数量。
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //进行安全性校验
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        //4.判断库存是否充足
        if(voucher.getStock()<1){
            //如果库存数量小于1，说明库存不足了
            return Result.fail("库存不足了");
        }
        // 说明还有库存，操作库存
        boolean success = seckillVoucherService
                .update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
//                .eq("stock",voucher.getStock())//这里使用了cas思想。在修改的时候判断 库存数量 跟 之前查询的数量 相同才会更新数据库，要不然就不更新了。这样做的坏处是会有大批量失败。所以使用下面的方式进行优化
                .gt("stock",0)//当大于0的时候不做判断，当库存数量等于0的时候再做这个判断
                .update();
        if(!success){
            //说明更新失败了
            return Result.fail("库存不足");
        }
        //创建订单。
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setId(orderId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
