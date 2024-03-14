package com.xmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
// DATA标签有set&get 构造器都有
public class RedisData {
    private LocalDateTime expireTime;
    // 为了不侵入原来的shop，加入这个data，可以存shop等各类数据
    private Object data;
}
