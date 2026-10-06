package com.example.ailab.contract.port;

import com.example.ailab.contract.dto.*;
import java.time.Instant;

/** Java受控可编辑导出，输入只有已批准内容与持久素材，不接任意路径或URL。 */
public interface PresentationPort {
    /** 字体部署缺失应在购买前发现；默认空实现仅供旧端口替身兼容。 */
    default void validateConfiguration(){ }
    /** 导出配置也属于本地输入版本，字体变化不能偷偷复用旧版文件。 */
    default String version(){return Presentation.VERSION;}
    /** 购买配图前按实际字体检查标题、正文和引用；默认空实现仅兼容旧端口替身。 */
    default java.util.List<Presentation.LayoutIssue> validateLayout(java.util.List<Media.Unit> units){return java.util.List.of();}
    /** 在剩余任务期限和空间内导出、重开结构检查及逐页预览。 */
    Presentation.Bundle export(Media.Preview preview,String inputHash,Instant deadline,long remainingBytes);
    /** 恢复先读checksum并重开文件；损坏只能有限本地重建。 */
    boolean intact(Presentation.Bundle bundle);
}
