package com.example.ailab.data.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBasePo> {
    @Select("SELECT * FROM knowledge_bases WHERE id=#{id} FOR UPDATE")
    KnowledgeBasePo selectLocked(@Param("id") long id);

    List<KnowledgeBasePo> listAuthorized(@Param("params") SqlParameters params);
}
