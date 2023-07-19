package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");
        if(user==null){
//            如果session里面没有user，说明这个人没登陆
            response.setStatus(401);
//            然后直接拦截
            return false;
        }
        //如果这个用户登录过了，把user信息存入threadLocal，用于给该线程的controller进行使用
        UserHolder.saveUser((User) user);
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
