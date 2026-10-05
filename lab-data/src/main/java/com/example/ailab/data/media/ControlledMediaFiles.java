package com.example.ailab.data.media;

import com.example.ailab.contract.dto.Media;
import com.example.ailab.contract.port.MediaFilePort;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.imageio.ImageIO;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 二进制媒体私有存储；不公开var，不接受模型磁盘路径或内部网络取图。 */
@Component
public class ControlledMediaFiles implements MediaFilePort {
    private final Path root;
    private final Set<String> allowedHosts;
    private final JavaCvProcessor processor=new JavaCvProcessor();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    /** 可编辑PPTX由受控导出器生成，文件落盘不等于已发布。 */
    public Media.FileFact writePresentation(String id,byte[] bytes){
        if(bytes.length<4||bytes[0]!='P'||bytes[1]!='K')throw invalid();
        return saveLocal(id,bytes,".pptx",com.example.ailab.contract.dto.Presentation.MIME);
    }
    /** 页面PNG是检查附件，不能替代正文文本框。 */
    public Media.FileFact writePagePreview(String id,byte[] bytes){
        if(!imageMime(bytes).equals("image/png"))throw invalid();return saveLocal(id,bytes,".png","image/png");
    }
    /** 只替换程序导出文件；临时名随机且finally清理，不修改付费原图片。 */
    private Media.FileFact saveLocal(String id,byte[] bytes,String extension,String mime){
        if(!id.matches("[a-f0-9-]{36}")||bytes.length<1||bytes.length>52428800)throw invalid();
        Path temporary=root.resolve(UUID.randomUUID()+".part");
        try{
            Files.createDirectories(root);Path target=root.resolve(id+extension);
            if(Files.isSymbolicLink(target))throw invalid();
            Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return new Media.FileFact(id+extension,mime,bytes.length,checksum(bytes));
        }catch(LabException e){throw e;}catch(Exception e){throw invalid();}
        finally{try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
    /** 下载主机白名单来自服务端资源核验；默认空表阻止任意URL，原生库由JavaCV加载。 */
    public ControlledMediaFiles(@Value("${lab.media.storage-root:var/media}") String root,
            @Value("${lab.media.download-hosts:}") String hosts,
            @Value("${lab.media.ffmpeg-path:}") String ffmpeg,@Value("${lab.media.ffprobe-path:}") String ffprobe) {
        this.root=Path.of(root).toAbsolutePath().normalize();
        this.allowedHosts=new HashSet<>(Arrays.asList(hosts.toLowerCase(Locale.ROOT).split(",")));
        // 兼容旧构造参数；正式执行仅使用随包JavaCV原生库，不读取外部可执行文件。
    }
    /** URL双重校验：仅精确白名单HTTPS主机且所有DNS地址均公网，拒绝userinfo／片段和重定向。 */
    public void validateUrl(String url) {
        try {
            var u=URI.create(url);
            if(!"https".equals(u.getScheme()) || u.getHost()==null || !allowedHosts.contains(u.getHost().toLowerCase(Locale.ROOT))
                    || u.getPort()!=-1 || u.getUserInfo()!=null || u.getFragment()!=null) throw invalid();
            for(var ip:InetAddress.getAllByName(u.getHost())) {
                byte[] b=ip.getAddress();
                if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()
                        || b.length==4 && (Byte.toUnsignedInt(b[0])==0 || Byte.toUnsignedInt(b[0])>=224 || Byte.toUnsignedInt(b[0])==100 && (Byte.toUnsignedInt(b[1])&192)==64)
                        || b.length==16 && (Byte.toUnsignedInt(b[0])&254)==252) throw invalid();
            }
        } catch(LabException e){throw e;}catch(Exception e){throw invalid();}
    }
    /** 有界收取与全响应期限；视频必须经JavaCV解码校验可识别视频流。 */
    public Media.FileFact fetch(String assetId,String url,String kind) {
        validateUrl(url);
        try {
            int maximum=kind.equals("IMAGE")?10485760:167772160;
            var response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),info->new BoundedBody(maximum));
            if(response.statusCode()!=200) throw invalid();
            byte[] bytes=response.body();
            String mime=kind.equals("IMAGE")?imageMime(bytes):"video/mp4";
            if(kind.equals("VIDEO") && (bytes.length<12 || !new String(bytes,4,4,java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp"))) throw invalid();
            var fact=save(assetId,bytes,mime);
            if(kind.equals("VIDEO")) probe(path(fact.storageKey()),"video");
            return fact;
        } catch(LabException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw invalid();}catch(Exception e){throw invalid();}
    }
    /** 本人／来源由调用者先核验，存储再核真实路径、大小与checksum，防替换或损坏。 */
    public byte[] read(Media.FileFact fact) {
        try {
            var p=path(fact.storageKey());
            if(Files.size(p)!=fact.size() || fact.size()>209715200L) throw invalid();
            var bytes=Files.readAllBytes(p);
            if(!checksum(bytes).equals(fact.checksum())) throw invalid(); return bytes;
        } catch(LabException e){throw e;}catch(Exception e){throw invalid();}
    }
    /** 解码计量音轨／视频真实时长。 */
    public long durationMs(Media.FileFact file,String stream){
        if(!Set.of("audio","video").contains(stream))throw invalid();read(file);return processor.duration(path(file.storageKey()),stream);
    }
    /** UTF-8镜头级SRT独立保存，字幕时间轴由程序提供。 */
    public Media.FileFact subtitles(String id,String text){
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length==0||bytes.length>65536)throw invalid();return save(id,bytes,"application/x-subrip");
    }
    /** JavaCV解码拼接并绘制字幕，不执行外部FFmpeg或滤镜表达式。 */
    public Media.FileFact concatenate(String id,List<Media.FileFact> shots,Media.FileFact subtitle,java.time.Instant deadline,long remaining){
        return concatenate(id,shots,subtitle,deadline,remaining,true);
    }
    /** 需要字幕烧录时才向处理器传递文字，其余输出保留独立SRT。 */
    public Media.FileFact concatenate(String id,List<Media.FileFact> shots,Media.FileFact subtitle,java.time.Instant deadline,long remaining,boolean burn){
        if(shots.isEmpty()||shots.size()>6||!subtitle.mime().equals("application/x-subrip"))throw invalid();
        String srt=burn?new String(read(subtitle),java.nio.charset.StandardCharsets.UTF_8):"";shots.forEach(this::read);
        return process(id,deadline,remaining,out->processor.concatenate(shots.stream().map(f->path(f.storageKey())).toList(),out,srt,deadline,remaining));
    }
    /** 本地处理回调只接收程序生成的临时路径。 */
    private interface LocalRender {void run(Path output);}
    /** 原子保存稳定UUID文件；失败删除本次临时文件，既有文件必须解码验证后才复用。 */
    private Media.FileFact process(String id,java.time.Instant deadline,long remaining,LocalRender action){
        if(!id.matches("[a-f0-9-]{36}")||remaining<1||!java.time.Instant.now().isBefore(deadline))throw invalid();
        Path temporary=root.resolve(UUID.randomUUID()+".render.part");
        try{
            Files.createDirectories(root);Path existing=root.resolve(id+".mp4");
            if(Files.exists(existing)){
                Path checked=path(id+".mp4");if(Files.size(checked)>remaining)throw invalid();probe(checked,"video");probe(checked,"audio");return save(id,Files.readAllBytes(checked),"video/mp4");
            }
            action.run(temporary);if(Files.size(temporary)>Math.min(167772160L,remaining))throw invalid();
            probe(temporary,"video");probe(temporary,"audio");return save(id,Files.readAllBytes(temporary),"video/mp4");
        }catch(LabException e){throw e;}catch(Exception e){throw invalid();}finally{try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
    /** 兼容旧文件校验入口，实际时长来自JavaCV解码。 */
    private double probe(Path file,String type){return processor.duration(file,type)/1000d;}
    /** 先读图片头尺寸，避免解码巨型压缩图；只支持真实PNG／JPEG且最多1600万像素。 */
    private String imageMime(byte[] bytes) {
        try(var in=ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(in); if(!readers.hasNext())throw invalid(); var reader=readers.next();
            try {
                reader.setInput(in); String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(format)||(long)reader.getWidth(0)*reader.getHeight(0)>16000000)throw invalid();
                if(reader.read(0)==null)throw invalid(); return format.equals("png")?"image/png":"image/jpeg";
            }finally{reader.dispose();}
        }catch(LabException e){throw e;}catch(Exception e){throw invalid();}
    }
    /** 稳定资产名及原子rename实现文件落盘，随机临时名使中断残留不阻断重试。 */
    private Media.FileFact save(String id,byte[] bytes,String mime) {
        if(!id.matches("[a-f0-9-]{36}")||bytes.length==0)throw invalid();
        Path temporary=root.resolve(UUID.randomUUID()+".part");
        try {
            Files.createDirectories(root); String extension=mime.equals("image/png")?".png":mime.equals("image/jpeg")?".jpg":mime.equals("audio/wav")?".wav":mime.equals("application/x-subrip")?".srt":".mp4";
            String key=id+extension; Path target=root.resolve(key);
            if(Files.exists(target)) {
                if(!checksum(Files.readAllBytes(target)).equals(checksum(bytes)))throw invalid();
            } else {
                Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW); Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            }
            return new Media.FileFact(key,mime,bytes.length,checksum(bytes));
        }catch(LabException e){throw e;}catch(Exception e){throw invalid();}
        finally{try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
    /** 仅UUID文件名且真实路径仍位于根目录，拒绝遍历和符号链接逃逸。 */
    private Path path(String key) {
        try {
            if(key==null||!key.matches("[a-f0-9-]{36}\\.(png|jpg|mp4|wav|srt|pptx)"))throw invalid();
            Path p=root.resolve(key).normalize(); if(!p.toRealPath().startsWith(root.toRealPath())||Files.isSymbolicLink(p))throw invalid();return p;
        }catch(LabException e){throw e;}catch(Exception e){throw invalid();}
    }
    /** 实际二进制SHA256而非文本后缀标记。 */
    private String checksum(byte[] bytes) { try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw invalid();} }
    /** 技术错误稳定脱敏；下载错误不能授权再次生成。 */
    private LabException invalid() { return new LabException("MEDIA_VALIDATION_FAILED","媒体地址、格式、大小、校验或取回无效"); }
    /** 全响应有界订阅，避免headers超时后无限流式读取。 */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int max; private Flow.Subscription subscription;
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(); private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        /** 图片／视频分别受有限空间约束。 */
        BoundedBody(int max){this.max=max;}
        /** 只返回完整媒体字节。 */
        public CompletionStage<byte[]> getBody(){return result;}
        /** 逐批消费。 */
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        /** 超限立即取消连接，不读无限响应。 */
        public void onNext(List<ByteBuffer> items){for(var item:items){if(bytes.size()+item.remaining()>max){subscription.cancel();result.completeExceptionally(new IllegalStateException("文件超限"));return;}byte[] b=new byte[item.remaining()];item.get(b);bytes.writeBytes(b);}subscription.request(1);}
        /** 错误不能交付半文件。 */
        public void onError(Throwable t){result.completeExceptionally(t);}
        /** 完整收取后才提交文件校验。 */
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
