package com.example.ailab.contract.dto;

import java.util.List;

/** S10本地导出事实，图片购买与Agent计划仍由原媒体契约负责。 */
public final class Presentation {
    public static final String MIME="application/vnd.openxmlformats-officedocument.presentationml.presentation";
    public static final String VERSION="pptx-layout-v1";
    /** 纯契约工具类不接受实例化。 */
    private Presentation() { }
    /** 实际字体与模板的文本预检结果，不携带来源正文或文件路径。 */
    public record LayoutIssue(String unitId,String code) { }
    public record Page(int number,String unitId,Media.FileFact preview,Long artifactId) { }
    public record Image(String unitId,String assetId,String kind,String checksum,String operationId,Media.ImageSource source) { }
    public record Check(long taskId,int previewVersion,int planVersion,String approvalHash,String inputHash,
                        String layoutVersion,String font,int slideCount,String structuralStatus,String qualityStatus,
                        List<String> warnings,List<Image> images,List<SourceDependency> sources,List<DocumentCoverage> coverage) {
        /** 结构通过与人工图文质量分开，不能从图片嵌入推断已看过图片。 */
        public Check {warnings=List.copyOf(warnings);images=List.copyOf(images);sources=List.copyOf(sources);coverage=List.copyOf(coverage);}
    }
    public record Bundle(Media.FileFact pptx,Long artifactId,List<Page> pages,Check check) {
        /** 发布后只有服务端登记的认证下载ID，没有静态URL。 */
        public Bundle {pages=List.copyOf(pages);}
    }
}
