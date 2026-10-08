package com.example.ailab.ai.media;

import com.example.ailab.contract.dto.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

/**
 * 媒体配置默认禁用；报价和目录支持须有真实依据，密钥只引用后端配置。
 */
@ConfigurationProperties("lab.media")
public record MediaProperties(boolean enabled, boolean workerEnabled, boolean externalDataAllowed,
                              String configurationVersion, String imageSubmitUrl, String videoSubmitUrl,
                              String videoQueryUrl, String credentialRef, String imageModel, String videoModel,
                              String imageSize, boolean videoSelectionVerified, boolean queryFreeVerified,
                              FeePrice imagePrice, FeePrice videoPrice, List<Media.CatalogItem> catalogs) {
    /**
     * 缺配置仅保留协议适配，不能自动开启付费或把示例价当报价。
     */
    public MediaProperties {
        configurationVersion = configurationVersion == null ? "s09-unverified-v1" : configurationVersion;
        imageSubmitUrl = imageSubmitUrl == null ? "https://open.bigmodel.cn/api/paas/v4/images/generations" : imageSubmitUrl;
        videoSubmitUrl = videoSubmitUrl == null ? "https://open.bigmodel.cn/api/paas/v4/videos/generations" : videoSubmitUrl;
        videoQueryUrl = videoQueryUrl == null ? "https://open.bigmodel.cn/api/paas/v4/async-result/{id}" : videoQueryUrl;
        credentialRef = credentialRef == null ? "MODEL_BACKUP_API_KEY" : credentialRef;
        imageModel = imageModel == null ? "glm-image" : imageModel;
        videoModel = videoModel == null ? "viduq1-text" : videoModel;
        imageSize = imageSize == null ? "1280x1280" : imageSize;
        catalogs = catalogs == null ? List.of() : List.copyOf(catalogs);
        if (!configurationVersion.matches("[A-Za-z0-9_.-]{1,64}") || !credentialRef.matches("[A-Z][A-Z0-9_]{1,63}")
                || !imageSize.matches("[0-9]{3,4}x[0-9]{3,4}") || catalogs.size() > 100)
            throw new IllegalArgumentException("媒体配置版本／凭证引用／目录超限");
    }
}
