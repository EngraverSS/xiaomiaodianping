package com.xmdp.config;

import com.xmdp.utils.LoginInterceptor;
import com.xmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    // 这里的@confiuration注解说明MvcConfig有springboot创建，可以用@Resource注解注入StringRedisTemplate
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录校验的拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        // 排除不需要拦截的路经,比如登录接口，做了登录校验岂不是乱套了
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                // order()设置拦截器执行优先级，数字越大，优先级越低，也就是RefreshTokenInterceptor先拦截
                ).order(1);

        // 拦截所有，token刷新的拦截器
        // new RefreshTokenInterceptor(stringRedisTemplate) 按照构造器的格式，将stringRedisTemplate传入
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
