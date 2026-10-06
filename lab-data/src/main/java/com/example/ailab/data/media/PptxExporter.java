package com.example.ailab.data.media;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.sl.usermodel.*;
import org.apache.poi.common.usermodel.fonts.FontGroup;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Java17可编辑PPTX导出；中文文本框与嵌入图片分离，页面PNG仅用于检查。 */
@Component
public class PptxExporter implements PresentationPort {
    private final MediaFilePort files;
    private final String font;
    private static final Color INK=new Color(26,45,64), ACCENT=new Color(25,91,125);
    /** 字体必须实际可用，避免静默以缺字字体验收中文。 */
    public PptxExporter(MediaFilePort files,@Value("${lab.presentation.font:Microsoft YaHei}") String font){this.files=files;this.font=font;}
    /** 字体和布局共同参与恢复摘要，不重启清空导出次数。 */
    public String version(){return Presentation.VERSION+":"+font;}
    /** 使用统一英文家族名核验字体，系统显示语言不影响部署检查。 */
    public void validateConfiguration(){if(!Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ENGLISH)).contains(font))throw error("PPT_FONT_UNAVAILABLE","指定中文字体未安装");}
    /** 批准正文不改写、不截断；超页／缺图／溢出明确停止并等待重新审批。 */
    public Presentation.Bundle export(Media.Preview p,String inputHash,Instant deadline,long remaining){
        limit(deadline);
        validateConfiguration();
        if(!p.status().equals("APPROVED")||p.units().isEmpty()||p.units().size()>11)throw error("PPT_PAGE_LIMIT","内容页加来源页必须在2～12页内，旧12内容页需重新预览");
        var warnings=new ArrayList<String>();var images=new ArrayList<Presentation.Image>();
        if(p.coverage().stream().anyMatch(c->!c.complete()))warnings.add("资料未完整覆盖，请同时核对未读范围");
        warnings.add("图文一致性、事实对象与教学效果仍须本人人工验收");
        try(var ppt=new XMLSlideShow()){
            ppt.setPageSize(new Dimension(960,540));ppt.getProperties().getCoreProperties().setTitle("私人笔记演示文稿");
            ppt.getProperties().getCoreProperties().setDescription("task="+p.taskId()+";preview="+p.previewVersion()+";plan="+p.planVersion()+";approval="+p.hash()+";input="+inputHash+";layout="+Presentation.VERSION);
            for(var unit:p.units()){
                limit(deadline);var slide=ppt.createSlide();slide.getBackground().setFillColor(Color.WHITE);
                var image=selected(p,unit);XSLFPictureData picture=null;
                if(image!=null){
                    byte[] bytes=files.read(image.file());
                    if(bytes.length>10485760)throw error("PPT_IMAGE_INVALID","单张配图超过10MB");
                    var decoded=ImageIO.read(new ByteArrayInputStream(bytes));
                    if(decoded==null||(long)decoded.getWidth()*decoded.getHeight()>16000000)throw error("PPT_IMAGE_INVALID","配图无法解码或超过像素限额");
                    picture=ppt.addPicture(bytes,image.file().mime().equals("image/png")?PictureData.PictureType.PNG:PictureData.PictureType.JPEG);
                    images.add(new Presentation.Image(unit.unitId(),image.assetId(),image.kind(),image.file().checksum(),image.operationId(),image.webSource()));
                }
                if(!Set.of("TITLE","TEXT","TWO_COLUMN","IMAGE_TEXT").contains(unit.layout()))throw error("PPT_LAYOUT_INVALID","PPT版式不属于受控目录");
                box(slide,unit.title(),new Rectangle2D.Double(48,32,864,82),34,28,true);
                if(picture!=null){
                    box(slide,unit.text(),new Rectangle2D.Double(48,136,416,314),24,18,false);
                    var shape=slide.createPicture(picture);shape.setAnchor(contain(picture,new Rectangle2D.Double(508,136,404,280)));
                    box(slide,image.kind().equals("GENERATED")?"AI生成概念示意":"网络事实配图，出处见备注及来源页",new Rectangle2D.Double(508,430,404,38),16,16,false);
                }else if(unit.layout().equals("TWO_COLUMN")){
                    int split=split(unit.text());
                    box(slide,unit.text().substring(0,split),new Rectangle2D.Double(48,136,416,314),24,18,false);
                    if(split<unit.text().length())box(slide,unit.text().substring(split),new Rectangle2D.Double(496,136,416,314),24,18,false);
                }else{
                    box(slide,unit.text(),new Rectangle2D.Double(48,136,864,314),26,18,false);
                }
                box(slide,"笔记引用 "+String.join("  ",unit.references()),new Rectangle2D.Double(48,480,864,42),14,14,false);
                notes(ppt,slide,unit.notes()+"\n\n笔记引用："+unit.references()+"\n"+sourceNotes(p)+(image==null?"":"\n配图："+image.kind()+"\nchecksum="+image.file().checksum()+"\noperation="+image.operationId()+"\n"+image.webSource()));
            }
            if(images.size()>8)throw error("PPT_IMAGE_LIMIT","每任务最多8张配图");
            var source=ppt.createSlide();source.getBackground().setFillColor(Color.WHITE);
            box(source,"来源与使用说明",new Rectangle2D.Double(48,32,864,82),34,28,true);
            String text="笔记来源\n"+p.sourceDependencies().stream().map(s->"D"+s.documentId()+"v"+s.documentVersion()+"  知识库 "+s.knowledgeBaseId()).collect(java.util.stream.Collectors.joining("\n"));
            if(!images.isEmpty())text+="\n\n配图来源\n"+images.stream().map(i->i.unitId()+"  "+(i.kind().equals("GENERATED")?"AI生成概念示意":shortLabel(i.source()))).collect(java.util.stream.Collectors.joining("\n"));
            text+="\n\n"+(p.coverage().stream().anyMatch(c->!c.complete())?"资料未完整覆盖，未读范围详见讲者备注":"覆盖范围详见讲者备注");
            box(source,text,new Rectangle2D.Double(48,136,864,342),22,18,false);notes(ppt,source,sourceNotes(p)+"\n配图完整出处\n"+images);
            var output=new ByteArrayOutputStream();ppt.write(output);byte[] bytes=output.toByteArray();
            if(bytes.length>52428800||bytes.length>remaining)throw error("BUDGET_EXCEEDED","PPTX及预览超过剩余空间");
            // 用真实重开结果检查关系、页数、文字与备注，不能只检查ZIP文件头。
            reopen(bytes,p.units().size()+1,images.size());
            var previewBytes=new ArrayList<byte[]>();long size=bytes.length;
            for(var slide:ppt.getSlides()){
                limit(deadline);var bitmap=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);var g=bitmap.createGraphics();
                try{g.setColor(Color.WHITE);g.fillRect(0,0,1280,720);g.scale(4d/3,4d/3);g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);slide.draw(g);}
                finally{g.dispose();}
                var png=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",png);byte[] page=png.toByteArray();size+=page.length;
                if(size>remaining)throw error("BUDGET_EXCEEDED","PPTX及逐页预览超过共享200MB空间");previewBytes.add(page);
            }
            limit(deadline);var deck=files.writePresentation(stable(inputHash+":pptx"),bytes);var pages=new ArrayList<Presentation.Page>();
            for(int i=0;i<previewBytes.size();i++){
                limit(deadline);var fact=files.writePagePreview(stable(inputHash+":page:"+i),previewBytes.get(i));
                pages.add(new Presentation.Page(i+1,i<p.units().size()?p.units().get(i).unitId():"SOURCES",fact,null));
            }
            var check=new Presentation.Check(p.taskId(),p.previewVersion(),p.planVersion(),p.hash(),inputHash,Presentation.VERSION,font,ppt.getSlides().size(),"PASSED","REQUIRES_HUMAN_REVIEW",warnings,images,p.sourceDependencies(),p.coverage());
            return new Presentation.Bundle(deck,null,pages,check);
        }catch(LabException e){throw e;}catch(Exception e){org.slf4j.LoggerFactory.getLogger(PptxExporter.class).error("event=presentation.export_failed",com.example.ailab.contract.error.DiagnosticFailure.sanitized(e));throw error("PPT_EXPORT_FAILED","PPTX导出、备注或逐页预览失败");}
    }
    /** 恢复只使用原文件／PNG与checksum，不触发任何图片／视频API。 */
    public boolean intact(Presentation.Bundle bundle){
        try{reopen(files.read(bundle.pptx()),bundle.check().slideCount(),bundle.check().images().size());for(var p:bundle.pages())if(ImageIO.read(new ByteArrayInputStream(files.read(p.preview())))==null)return false;return true;}
        catch(RuntimeException|IOException e){return false;}
    }
    /** 必要配图须唯一匹配已批准单位，事实图还必须有原始出处与对象说明。 */
    private Media.Asset selected(Media.Preview p,Media.Unit u){
        if(u.imageMode().equals("NONE"))return null;
        String kind=u.imageMode().equals("GENERATED")?"GENERATED":u.imageMode().equals("WEB_SEARCH")?"WEB_SEARCH":"INVALID";
        var candidates=p.assets().stream().filter(a->a.unitId().equals(u.unitId())&&a.kind().equals(kind)).toList();
        if(candidates.size()!=1)throw error("PPT_IMAGE_MISSING","所需配图缺失或批准选择不唯一");
        var a=candidates.get(0);
        if(!Set.of("image/png","image/jpeg").contains(a.file().mime()))throw error("PPT_IMAGE_INVALID","配图须为真实PNG或JPEG");
        if(kind.equals("WEB_SEARCH")&&(a.webSource()==null||a.webSource().sourcePageUrl()==null||a.webSource().imageUrl()==null||a.webSource().objectAndPeriod()==null||a.webSource().license()==null))throw error("PPT_IMAGE_SOURCE_REQUIRED","事实配图缺少出处、对象或使用条件");
        return a;
    }
    /** 按实际字体换行并逐级适配字号，最低字号仍放不下时失败，不截断批准正文。 */
    private void box(XSLFSlide slide,String text,Rectangle2D anchor,int initial,int minimum,boolean title){
        if(text==null||text.isBlank())throw error("PPT_CONTENT_INVALID","页面文本不能为空");
        if(new Font(font,Font.PLAIN,initial).canDisplayUpTo(text)!=-1)throw error("PPT_FONT_GLYPH_MISSING","指定字体无法显示批准文本中的字符");
        String wrapped=null;int selected=initial;
        for(int size=initial;size>=minimum;size--){String candidate=wrap(text,anchor.getWidth()-8,size,title);int lines=candidate.split("\n",-1).length;
            if(lines*size*1.35+8<=anchor.getHeight()){wrapped=candidate;selected=size;break;}}
        if(wrapped==null)throw error("PPT_TEXT_OVERFLOW","批准文字超出版式，需缩短内容或重新审批拆页");
        var shape=slide.createTextBox();shape.setAnchor(anchor);shape.setInsets(new Insets2D(4,4,4,4));shape.setWordWrap(false);
        shape.setText(wrapped);
        for(var paragraph:shape.getTextParagraphs()){
            paragraph.setSpaceBefore(0d);paragraph.setSpaceAfter(0d);paragraph.setLineSpacing(110d);
            // 默认字体组随正文语言变化；中英混合引用必须同时显式设置，防止英文回退到主题字体。
            for(var run:paragraph.getTextRuns()){run.setFontFamily(font,FontGroup.LATIN);run.setFontFamily(font,FontGroup.EAST_ASIAN);run.setFontSize((double)selected);run.setBold(title);run.setFontColor(title?ACCENT:INK);}
        }
        if(shape.getTextHeight()>anchor.getHeight()-4)throw error("PPT_TEXT_OVERFLOW","真实字体高度超出批准版式："+shape.getTextHeight()+"／"+anchor.getHeight());
    }
    /** 码点换行不切开代理对，正文保持原始字符顺序；换行只影响版式。 */
    private String wrap(String text,double width,int size,boolean bold){
        Font actual=new Font(font,bold?Font.BOLD:Font.PLAIN,size);var metrics=new java.awt.font.FontRenderContext(null,true,true);var result=new StringBuilder();
        for(String line:text.split("\n",-1)){if(result.length()>0)result.append('\n');var row=new StringBuilder();
            for(int cp:line.codePoints().toArray()){String value=new String(Character.toChars(cp));if(row.length()>0&&actual.getStringBounds(row+value,metrics).getWidth()>width){result.append(row).append('\n');row.setLength(0);}row.append(value);}result.append(row);}
        return result.toString();
    }
    /** 优先段落边界，两侧均有正文才分栏；无法分栏时保留完整正文在左栏。 */
    private int split(String text){
        int midpoint=text.offsetByCodePoints(0,text.codePointCount(0,text.length())/2);
        for(int newline=text.indexOf('\n',midpoint);newline>=0;newline=text.indexOf('\n',newline+1))
            if(!text.substring(0,newline).isBlank()&&!text.substring(newline).isBlank())return newline;
        int candidate=midpoint;
        if(candidate>0&&!text.substring(0,candidate).isBlank()&&!text.substring(candidate).isBlank())return candidate;
        return text.length();
    }
    /** 图片按原比例完整显示，不裁掉事实或拉伸。 */
    private Rectangle2D contain(XSLFPictureData picture,Rectangle2D frame){var d=picture.getImageDimension();double scale=Math.min(frame.getWidth()/d.width,frame.getHeight()/d.height);double w=d.width*scale,h=d.height*scale;return new Rectangle2D.Double(frame.getCenterX()-w/2,frame.getCenterY()-h/2,w,h);}
    /** 使用真正的BODY备注占位符，PowerPoint讲者备注保留可编辑文本。 */
    private void notes(XMLSlideShow ppt,XSLFSlide slide,String text){
        var note=ppt.getNotesSlide(slide);var body=note.getShapes().stream().filter(s->s instanceof XSLFTextShape t&&t.getTextType()==Placeholder.BODY).map(s->(XSLFTextShape)s).findFirst().orElseGet(()->{var b=note.createTextBox();b.setPlaceholder(Placeholder.BODY);return b;});body.setText(text);
        for(var p:body.getTextParagraphs())for(var r:p.getTextRuns()){r.setFontFamily(font,FontGroup.LATIN);r.setFontFamily(font,FontGroup.EAST_ASIAN);}
    }
    /** 完整笔记来源、覆盖和批准关联写在备注，来源页只显示可读摘要。 */
    private String sourceNotes(Media.Preview p){return "task="+p.taskId()+" previewVersion="+p.previewVersion()+" planVersion="+p.planVersion()+" approvalHash="+p.hash()+"\n笔记来源="+p.sourceDependencies()+"\n覆盖／未读="+p.coverage();}
    /** 来源页标签限长，原网址和许可原文仍保留在备注及检查JSON。 */
    private String shortLabel(Media.ImageSource s){String value=s.title()==null?s.sourcePageUrl():s.title();return value.codePointCount(0,value.length())>60?value.substring(0,value.offsetByCodePoints(0,60))+"…":value;}
    /** 重开确认每页有编辑文字与BODY备注，图片不占整页正文。 */
    private void reopen(byte[] bytes,int count,int images)throws IOException{
        try(var reopened=new XMLSlideShow(new ByteArrayInputStream(bytes))){
            if(reopened.getSlides().size()!=count)throw error("PPT_STRUCTURE_INVALID","文件页数不一致");
            if(reopened.getSlides().stream().flatMap(s->s.getShapes().stream()).filter(s->s instanceof XSLFPictureShape).count()!=images)throw error("PPT_STRUCTURE_INVALID","嵌入配图数量不一致");
            for(var slide:reopened.getSlides())if(slide.getShapes().stream().noneMatch(s->s instanceof XSLFTextShape t&&!t.getText().isBlank())||slide.getNotes()==null||slide.getNotes().getShapes().stream().noneMatch(s->s instanceof XSLFTextShape t&&t.getTextType()==Placeholder.BODY&&!t.getText().isBlank()))throw error("PPT_STRUCTURE_INVALID","文件缺少可编辑文字或讲者备注");
        }
    }
    /** 输入摘要稳定关联本地文件名，不接受客户端文件名。 */
    private String stable(String value){return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
    /** 单次导出120秒与任务剩余期限取小值，由编排器给定统一期限。 */
    private void limit(Instant deadline){if(Thread.currentThread().isInterrupted()||!Instant.now().isBefore(deadline))throw error("PPT_EXPORT_TIMEOUT","本地导出期限耗尽");}
    /** 只返回稳定错误码，不泄露路径、来源正文或底层异常。 */
    private LabException error(String code,String message){return new LabException(code,message);}
}
