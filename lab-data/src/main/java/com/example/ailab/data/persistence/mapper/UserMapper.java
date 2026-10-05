package com.example.ailab.data.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ailab.data.persistence.po.UserPo;

/**
 * 常规 CRUD 使用 MyBatis-Plus，关键并发操作仍使用显式 SQL。
 */
public interface UserMapper extends BaseMapper<UserPo> {
    @org.apache.ibatis.annotations.Select({"<script>SELECT * FROM users WHERE id=#{id}",
            "<if test='lock'>FOR UPDATE</if></script>"})
    UserPo selectCurrent(@org.apache.ibatis.annotations.Param("id") long id,
                         @org.apache.ibatis.annotations.Param("lock") boolean lock);

    @org.apache.ibatis.annotations.Select("SELECT * FROM users ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    java.util.List<UserPo> selectPageUsers(@org.apache.ibatis.annotations.Param("offset") int offset,
                                         @org.apache.ibatis.annotations.Param("limit") int limit);

    @org.apache.ibatis.annotations.Select("SELECT u.* FROM auth_tokens t JOIN users u ON u.id=t.user_id WHERE t.token_hash=#{hash} AND t.revoked=FALSE AND t.expires_at>CURRENT_TIMESTAMP(6) AND u.enabled=TRUE AND u.permission_version=t.permission_version")
    UserPo selectAuthenticated(@org.apache.ibatis.annotations.Param("hash") String hash);
}
