package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    //缓存中的逻辑过期时间
    private LocalDateTime expireTime;
    //缓存中的数据（这里面的数据可能存在不一致）
    private Object data;
}