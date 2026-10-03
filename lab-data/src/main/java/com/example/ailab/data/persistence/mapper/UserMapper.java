package com.example.ailab.data.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ailab.data.persistence.po.UserPo;

/**
 * 常规 CRUD 使用 MyBatis-Plus，关键并发操作仍使用显式 SQL。
 */
public interface UserMapper extends BaseMapper<UserPo> {
}
