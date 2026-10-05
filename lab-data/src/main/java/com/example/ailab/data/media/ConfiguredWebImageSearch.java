package com.example.ailab.data.media;
import com.example.ailab.contract.dto.Media;
import com.example.ailab.contract.port.WebImageSearchPort;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** 外部事实图片搜索适配；当前没有实际搜索资源，不把协议替身算真实检索。 */
@Component
public class ConfiguredWebImageSearch implements WebImageSearchPort {
    private final String endpoint;private final boolean freeVerified;private final ControlledMediaFiles files;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    /** 显式注册搜索服务返回candidates协议，收费服务在具备单独预付审批前保持禁用。 */
    public ConfiguredWebImageSearch(@Value("${lab.media.image-search-url:}")String endpoint,@Value("${lab.media.image-search-free-verified:false}")boolean freeVerified,ControlledMediaFiles files){this.endpoint=endpoint;this.freeVerified=freeVerified;this.files=files;}
    /** 地址及免费规则未核验时不可向外发私人关键词。 */
    public boolean enabled(){return !endpoint.isBlank()&&freeVerified;}
    /** 仅有界关键词及五候选，不下载缩略图冒充已核验原图。 */
    public List<Media.ImageSource> search(String query){
        if(!enabled())throw new LabException("SEARCH_UNAVAILABLE","事实图片搜索未配置或收费尚未确认");
        if(query==null||query.isBlank()||query.length()>200)throw LabException.invalid("图片关键词超限");
        files.validateUrl(endpoint);
        try {
            var request=HttpRequest.newBuilder(URI.create(endpoint+"?q="+URLEncoder.encode(query,java.nio.charset.StandardCharsets.UTF_8)+"&count=5")).timeout(Duration.ofSeconds(5)).GET().build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var in=response.body()){
                byte[] bytes=in.readNBytes(32769);if(response.statusCode()!=200||bytes.length>32768)throw new LabException("SEARCH_UNAVAILABLE","搜索响应不可用或超限");
                var root=new ObjectMapper().readTree(bytes);var values=new ArrayList<Media.ImageSource>();
                if(!root.path("candidates").isArray()||root.path("candidates").size()>5)throw new LabException("SEARCH_UNAVAILABLE","搜索候选协议不匹配");
                for(var c:root.path("candidates")){
                    for(String key:List.of("sourcePageUrl","imageUrl","title","license","objectAndPeriod"))if(!c.path(key).isTextual()||c.path(key).asText().isBlank()||c.path(key).asText().length()>4096)throw new LabException("SEARCH_UNAVAILABLE","搜索候选缺出处或使用信息");
                    files.validateUrl(c.path("imageUrl").asText());files.validateUrl(c.path("sourcePageUrl").asText());
                    values.add(new Media.ImageSource(UUID.randomUUID().toString(),query,c.path("sourcePageUrl").asText(),c.path("imageUrl").asText(),c.path("title").asText(),c.path("author").asText("UNKNOWN"),c.path("license").asText(),c.path("objectAndPeriod").asText(),Instant.now()));
                }return List.copyOf(values);
            }
        }catch(LabException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new LabException("SEARCH_UNAVAILABLE","搜索中断");}catch(Exception e){throw new LabException("SEARCH_UNAVAILABLE","搜索失败");}
    }
}
