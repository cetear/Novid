package com.example.ailab.contract.port;
import com.example.ailab.contract.dto.Media;

/** 受控媒体文件存储；不接用户路径，不公开静态目录。 */
public interface MediaFilePort {
    /** 本地PPTX已完成结构检查后原子写入；可替换同键损坏的本地导出。 */
    default Media.FileFact writePresentation(String id,byte[] bytes){throw new UnsupportedOperationException();}
    /** PPT页预览只作检查，PPT正文保持可编辑。 */
    default Media.FileFact writePagePreview(String id,byte[] bytes){throw new UnsupportedOperationException();}
    /** 有界取回公开白名单地址，拒绝内部网络、重定向、非真实媒体与超额文件。 */
    Media.FileFact fetch(String assetId, String url, String kind);
    /** 按描述复核大小和checksum后读取，调用方先检查本人及来源。 */
    byte[] read(Media.FileFact file);
    /** 真实视频处理使用项目内JavaCV；缺运行时须在付费前明确不可用。 */
    default boolean videoRuntimeAvailable() { return false; }
    /** 读取真实流时长并向上取整毫秒；音轨不存在返回0，不以模型估计替代。 */
    default long durationMs(Media.FileFact file,String stream){throw new UnsupportedOperationException();}
    /** 实测时间轴生成UTF-8镜头级字幕，保存为可认证下载资产。 */
    default Media.FileFact subtitles(String assetId,String srt){throw new UnsupportedOperationException();}
    /** 仅拼接受控镜头并烧录已校验SRT，不能执行模型Shell或外部协议。 */
    default Media.FileFact concatenate(String assetId,java.util.List<Media.FileFact> shots,Media.FileFact subtitles,java.time.Instant deadline,long remainingBytes){throw new UnsupportedOperationException();}
    /** 独立SRT不要求烧录；兼容旧调用方的显式烧录入口。 */
    default Media.FileFact concatenate(String assetId,java.util.List<Media.FileFact> shots,Media.FileFact subtitles,java.time.Instant deadline,long remainingBytes,boolean burn){return concatenate(assetId,shots,subtitles,deadline,remainingBytes);}
}
