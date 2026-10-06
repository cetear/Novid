package com.example.ailab.data.media;

import com.example.ailab.contract.error.LabException;
import org.bytedeco.javacv.*;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import java.nio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.awt.Color;
import java.awt.Font;

/** 项目内媒体处理；JavaCV调用随包FFmpeg原生库，不创建外部命令进程。 */
public final class JavaCvProcessor {
    private static final Semaphore SLOTS=new Semaphore(1);
    private static final int RATE=48000,FPS=30;
    private static final long MAX_MS=540000;
    private volatile Boolean ready;
    /** 首次检查加载器和编码器，缺原生包或编码能力在付费前显式拒绝。 */
    public synchronized boolean available(){
        if(ready!=null)return ready;
        try{
            // 原生缓存默认放项目运行目录，可由JVM参数覆盖；不写用户主目录。
            if(System.getProperty("org.bytedeco.javacpp.cachedir")==null)System.setProperty("org.bytedeco.javacpp.cachedir",Path.of("var/media-native-cache").toAbsolutePath().toString());
            FFmpegFrameGrabber.tryLoad();FFmpegFrameRecorder.tryLoad();avutil.av_log_set_level(avutil.AV_LOG_QUIET);
            ready=avcodec.avcodec_find_encoder(avcodec.AV_CODEC_ID_H264)!=null&&avcodec.avcodec_find_encoder(avcodec.AV_CODEC_ID_AAC)!=null;
        }
        catch(Throwable unavailable){ready=false;}return ready;
    }
    /** 解码真实流并按时间戳计量，不以请求时长或模型估计替代；有界串行原生工作。 */
    public long duration(Path file,String type){
        acquire();try(var grabber=open(file)){
            long end=0,start=-1;int count=0;var deadline=Instant.now().plusSeconds(30);
            for(Frame frame;(frame=grabber.grab())!=null;){
                guard(deadline,file,209715200L);if(++count>100000)throw invalid();
                boolean match=type.equals("video")?frame.image!=null:frame.samples!=null;if(!match)continue;
                long timestamp=Math.max(0,frame.timestamp);if(start<0)start=timestamp;
                long length=frame.image!=null?(long)Math.ceil(1000000d/Math.max(1,grabber.getVideoFrameRate())):sampleDuration(frame);
                end=Math.max(end,timestamp+length);
            }
            if(start<0&&type.equals("audio"))return 0; // 无音轨是明确事实，不伪造静音时长。
            long ms=(end-start+999)/1000;if(start<0||ms<=0||ms>MAX_MS)throw invalid();return ms;
        }catch(LabException e){throw e;}catch(Exception e){org.slf4j.LoggerFactory.getLogger(JavaCvProcessor.class).error("event=media.local_failed",com.example.ailab.contract.error.DiagnosticFailure.sanitized(e));throw invalid();}finally{SLOTS.release();}
    }
    /** 统一重编码连续时间轴，原生轨保留；仅需字幕时通过Java绘制，避免滤镜字符串执行。 */
    public void concatenate(java.util.List<Path> clips,Path output,String srt,Instant deadline,long maximum){
        acquire();try{
            boolean withAudio=false;for(Path clip:clips)try(var g=open(clip)){if(g.getAudioCodec()!=avcodec.AV_CODEC_ID_NONE)withAudio=true;}
            try(var recorder=recorder(output,withAudio?2:0);var converter=new Java2DFrameConverter()){
            var cues=cues(srt);long offset=0;
            for(Path clip:clips){Frame last=null;try(var g=open(clip)){
                if(g.getImageWidth()<1)throw invalid();long nextVideo=0,audioUs=0;
                for(Frame f;(f=g.grab())!=null;){
                    guard(deadline,output,maximum);
                    if(f.image!=null){
                        if(f.timestamp+1000<nextVideo)continue;
                        try(Frame scaled=resize(f,converter)){
                        while(nextVideo<=f.timestamp+1000){
                            paint(scaled,converter,cues,(offset+nextVideo)/1000);recorder.record(scaled);nextVideo+=1000000L/FPS;
                        }
                        if(last!=null)last.close();last=scaled.clone();
                        }
                    }
                    if(f.samples!=null){long duration=sampleDuration(f);recorder.recordSamples(f.sampleRate,f.audioChannels,f.samples);audioUs+=duration;}
                }
                // 每片段补齐短流，防止多个原生AAC尾部误差逐镜头累积；只补静音／末帧，不裁旁白。
                while(nextVideo<audioUs){guard(deadline,output,maximum);if(last==null)throw invalid();recorder.record(last);nextVideo+=1000000L/FPS;}
                long padding=nextVideo-audioUs;
                if(withAudio&&padding>0){
                    // 允许已审批无台词镜头补静音，逐块有界写入；全片无声时不创建音轨。
                    while(padding>0){guard(deadline,output,maximum);long chunk=Math.min(padding,1000000L);recorder.recordSamples(RATE,2,FloatBuffer.allocate((int)(chunk*RATE/1000000L)*2));padding-=chunk;}
                }
                offset+=nextVideo;if(offset>MAX_MS*1000)throw invalid();
            }finally{if(last!=null)last.close();}}
        }}catch(LabException e){throw e;}catch(Exception e){org.slf4j.LoggerFactory.getLogger(JavaCvProcessor.class).error("event=media.local_failed",com.example.ailab.contract.error.DiagnosticFailure.sanitized(e));throw invalid();}finally{SLOTS.release();}
    }
    /** 流式读取受控本地文件；禁止通过媒体容器再访问网络协议。 */
    private FFmpegFrameGrabber open(Path path)throws Exception{
        var g=new FFmpegFrameGrabber(path.toFile());g.setOption("protocol_whitelist","file,pipe");g.setTimeout(10000000);
        // JavaCV 1.5.14录制器转换输入时沿用输出声道布局，直接传单声道会失真甚至原生越界。
        // 在解码端统一为录制器的双声道布局；采样率／样本格式仍由解码器据实返回。
        g.setAudioChannels(2);
        try{g.start();return g;}catch(Exception e){g.close();throw e;}
    }
    /** 无声成片不凭空增加静音音轨，有声成片统一双声道。 */
    private FFmpegFrameRecorder recorder(Path path,int channels)throws Exception{
        var r=new FFmpegFrameRecorder(path.toFile(),1280,720,channels);r.setFormat("mp4");r.setVideoCodec(avcodec.AV_CODEC_ID_H264);r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        r.setFrameRate(FPS);r.setVideoBitrate(2500000);r.setAudioCodec(avcodec.AV_CODEC_ID_AAC);r.setSampleRate(RATE);r.setAudioBitrate(128000);
        try{r.start();return r;}catch(Exception e){r.close();throw e;}
    }
    /** 保持画幅并补黑边，不把低分辨率素材标成原生高清。 */
    private Frame resize(Frame input,Java2DFrameConverter converter){
        var source=converter.getBufferedImage(input);var target=new java.awt.image.BufferedImage(1280,720,java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
        var g=target.createGraphics();try{g.setColor(Color.BLACK);g.fillRect(0,0,1280,720);double scale=Math.min(1280d/source.getWidth(),720d/source.getHeight());int w=(int)(source.getWidth()*scale),h=(int)(source.getHeight()*scale);g.drawImage(source,(1280-w)/2,(720-h)/2,w,h,null);}finally{g.dispose();}
        return converter.getFrame(target).clone();
    }
    private record Cue(long start,long end,String text) { }
    /** 只读取程序生成的镜头级SRT，不解释HTML、ASS或磁盘路径。 */
    private java.util.List<Cue> cues(String srt){
        if(srt==null||srt.isBlank())return java.util.List.of();var list=new ArrayList<Cue>();
        for(String block:srt.trim().split("\\r?\\n\\r?\\n")){String[] lines=block.split("\\r?\\n");if(lines.length!=3)throw invalid();String[] times=lines[1].split(" --> ");if(times.length!=2)throw invalid();list.add(new Cue(time(times[0]),time(times[1]),lines[2]));}return list;
    }
    /** 字幕时间仅接受固定整数格式。 */
    private long time(String value){if(!value.matches("\\d{2}:\\d{2}:\\d{2},\\d{3}"))throw invalid();var p=value.split("[:,]");return Long.parseLong(p[0])*3600000+Long.parseLong(p[1])*60000+Long.parseLong(p[2])*1000+Long.parseLong(p[3]);}
    /** 实测镜头字幕逐帧绘制，字体缺中文字形时拒绝伪装可读字幕。 */
    private void paint(Frame frame,Java2DFrameConverter converter,java.util.List<Cue> cues,long ms){
        var cue=cues.stream().filter(c->ms>=c.start()&&ms<c.end()).findFirst();if(cue.isEmpty())return;
        var image=converter.getBufferedImage(frame);var g=image.createGraphics();try{
            var font=new Font("Dialog",Font.PLAIN,28);if(font.canDisplayUpTo(cue.get().text())!=-1)throw new LabException("MEDIA_FONT_UNAVAILABLE","字幕字体缺少所需字形");
            g.setFont(font);var metrics=g.getFontMetrics();var lines=new ArrayList<String>();String line="";
            for(int cp:cue.get().text().codePoints().toArray()){String next=line+new String(Character.toChars(cp));if(metrics.stringWidth(next)>1160&&!line.isEmpty()){lines.add(line);line="";}line+=new String(Character.toChars(cp));}lines.add(line);
            if(lines.size()>6)throw invalid();int y=690-(lines.size()-1)*34;
            for(String text:lines){int x=(1280-metrics.stringWidth(text))/2;g.setColor(Color.BLACK);g.fillRect(x-8,y-29,metrics.stringWidth(text)+16,34);g.setColor(Color.WHITE);g.drawString(text,x,y);y+=34;}
            // 绘制后的像素复制回自有帧，避免用转换器的可复用缓存替换帧资源所有权。
            Frame painted=converter.convert(image);ByteBuffer source=(ByteBuffer)painted.image[0],target=(ByteBuffer)frame.image[0];
            source.rewind();target.rewind();target.put(source);target.rewind();
        }finally{g.dispose();}
    }
    /** 交织／平面音频按真实样本数量计算时长，不以视频帧数代替。 */
    private long sampleDuration(Frame f){if(f.sampleRate<=0||f.audioChannels<=0||f.samples==null||f.samples.length==0)throw invalid();long samples=f.samples[0].remaining();if(f.samples.length==1)samples/=f.audioChannels;return samples*1000000L/f.sampleRate;}
    /** 有界执行槽防止原生内存随任务数增长，不在HTTP线程等待。 */
    private void acquire(){if(!available()||!SLOTS.tryAcquire())throw new LabException("MEDIA_CAPABILITY_UNAVAILABLE","媒体运行时不可用或处理槽已占用");}
    /** 每批帧检查期限／取消／空间；本机原生卡死仍须单独故障验收。 */
    private void guard(Instant deadline,Path output,long maximum){try{if(Thread.currentThread().isInterrupted()||!Instant.now().isBefore(deadline)||Files.exists(output)&&Files.size(output)>maximum)throw invalid();}catch(java.io.IOException e){throw invalid();}}
    /** 解码失败只返回稳定错误，不泄漏本地路径或提供方地址。 */
    private static LabException invalid(){return new LabException("MEDIA_VALIDATION_FAILED","JavaCV媒体流或本地处理校验失败");}
}
