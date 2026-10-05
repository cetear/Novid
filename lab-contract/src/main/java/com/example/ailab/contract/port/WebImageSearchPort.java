package com.example.ailab.contract.port;
import com.example.ailab.contract.dto.Media;
import java.util.List;
/** 搜索事实图元数据，不允许用生成图伪装检索结果。 */
public interface WebImageSearchPort {
    /** 仅必要关键词，至多五候选；未配置或收费未获批准须明确拒绝。 */
    List<Media.ImageSource> search(String query);
    /** 服务未配置或收费未知时不对外发关键词。 */
    default boolean enabled(){return false;}
}
