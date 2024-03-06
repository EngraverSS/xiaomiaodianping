package com.xmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.db.handler.HandleHelper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xmdp.dto.LoginFormDTO;
import com.xmdp.dto.Result;
import com.xmdp.dto.UserDTO;
import com.xmdp.entity.User;
import com.xmdp.mapper.UserMapper;
import com.xmdp.service.IUserService;
import com.xmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.xmdp.utils.RedisConstants.*;
import static com.xmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    // 在构建项目时已经做好对redis的配置，可以直接注入springdata提供的api，对redis进行操作
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 基于Redis实现短信验证码发送
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到redis set key value ex 120（默认为秒）
        // opsForValue()用于操作字符串相关的操作，phone为key, code为value, LOGIN_CODE_TTL为时间, TimeUnit.MINUTES为单位
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5.发送验证码 模拟不是正式的，公司中有现成的方法可以调用
        log.debug("发送短信验证码成功，验证码为：{}", code);
        // 返回ok
        return Result.ok();
    }

    // 基于Redis实现短信验证码登录
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确！");
        }
        // 2.从redis校验验证码（获取key为code的属性值value）
        // 这里同样调用opsForValue()对字符串进行操作，get(key)就能获得存储的code
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        if (cacheCode == null || !cacheCode.equals(code)) {
            // 3.不一致，报错
            return Result.fail("验证码错误");
        }
        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        // 这里可以直接使用query()，mybatis plus
        // 查询可以query().eq("phone", phone)可以返回一个list，也可以返回一个one
        User user = query().eq("phone", phone).one();
        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户保存
            user = createUserWithPhone(phone);
        }
        // 7.保存用户到redis中
        // 7.1.随机生成token，作为登录令牌
        // hutool提供的UUID，这里的toString(true)的true代表的是不带中划线的简单UUID
        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 使用BeanUtil将一个bean转变成一个Map
        // 这里需要对UserDTO的id进行类型转换Long -> String否则进入Redis中就会报错
        // stringRedisTemplate要求<String, String>，而我们的id在copy至value的时候是Long
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                // CopyOptions.create()数据拷贝的一个选项
                CopyOptions.create()
                        // 忽略空值
                        .setIgnoreNullValue(true)
                        // 对字段值的修改器(字段名, 字段值) -> 字段值.toString()
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        // 7.3.存储
        // 这里对Hash进行处理opsForHash()
        // putAll()传入的是一个Map可以储存多个键值对，所以需要回去对userDTO进行处理
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8.返回token
        return Result.ok(token);
    }

    // 原始基于Session实现短信验证码发送
/*

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到session
        session.setAttribute("code", code);
        // 5.发送验证码 模拟不是正式的，公司中有现成的方法可以调用
        log.debug("发送短信验证码成功，验证码为：{}", code);
        // 返回ok
        return Result.ok();
    }
*/

    // 原始基于Session实现短信验证码登录
   /* @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确！");
        }
        // 2.校验验证码（获取key为code的属性值value）
        Object cacheCode = session.getAttribute("code");

        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            // 3.不一致，报错
            return Result.fail("验证码错误");
        }
        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        // 这里可以直接使用query()，mybatis plus
        // 查询可以query().eq("phone", phone)可以返回一个list，也可以返回一个one
        User user = query().eq("phone", phone).one();
        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户保存
            user = createUserWithPhone(phone);
        }
        // 7.保存用户到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }*/

    // 专门用于保存用户的方法，在上面的步骤6中使用
    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        // 这里save()也是mybytis的方法
        save(user);
        return user;
    }
}
