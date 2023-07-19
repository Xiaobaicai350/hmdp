package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 昊昊
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.首先校验手机号是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不合法，直接返回错误信息
            return Result.fail("手机号格式错误，请检查您的手机号是否合法");
        }
        //3.如果符合就生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        session.setAttribute("code",code);
        //5.给用户发送验证码(这里只是模拟一下)
        log.debug("发送验证码成功，验证码为：{}",code);
        return Result.ok();
    }

    /**
     * 用户的登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号是否合法
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不合法，就直接返回异常
            return Result.fail("手机号非法。");
        }
        //3.从session中取出验证码
        Object code = session.getAttribute("code");
        String userCode = loginForm.getCode();
        //4.对比，如果不一致，直接报错
        if (userCode==null||!userCode.equals(code.toString())){
            return Result.fail("验证码输入错误");
        }
        //5.如果一致的话，从数据库中查询数据，得到此用户
        User user = query().eq("phone", phone).one();
        //6.判断这个用户是否存在。
        if (user==null) {
            //7.如果不存在，就先创建这个用户
            user =  createUserWithPhone(phone);
        }
        //8.如果存在，就保存这个用户到session中
//        这里进行优化，可以不往session里面存储整个user对象，因为session空间很宝贵
        session.setAttribute("user",BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(5));
        save(user);
        return user;
    }
}
