package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

public class LoginInterceptor implements HandlerInterceptor {
    StringRedisTemplate stringRedisTemplate;

//    这里需要通过构造方法进行传入，因为这个类未被spring所管理
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//       从请求头中获取token
        String token = request.getHeader("authorization");
        String key=LOGIN_USER_KEY + token;
//      从redis中获取用户信息。
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if(userMap.isEmpty()){
//            如果session里面没有user，说明这个人没登陆
            response.setStatus(401);
//            然后直接拦截
            return false;
        }
//        把map中的数据还原回对象,第三个参数是是否忽略转换过程中的错误
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(),false);
        //如果这个用户登录过了，把user信息存入threadLocal，用于给该线程的controller进行使用
        UserHolder.saveUser(userDTO);

        //刷新token的有效期
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return true;
    }

    //视图渲染之后执行
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//  用于移除用户，防止内存泄露
        UserHolder.removeUser();
    }
}
