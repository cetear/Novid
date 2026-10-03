package com.example.ailab.web.dto;

import jakarta.validation.constraints.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.SourceDependency;

import java.util.List;

/**
 * HTTP 格式校验只放在 web DTO，contract 保持纯 Java。
 */
public final class Requests {
    /**
     * 无须创建外层 DTO 容器。
     */
    private Requests() {
    }

    public record Login(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {
    }

    public record Password(@NotBlank @Size(max = 128) String oldPassword,
                           @NotBlank @Size(min = 12, max = 64) String newPassword) {
    }

    public record CreateUser(@NotBlank @Size(min = 3, max = 64) String username) {
    }

    public record UserUpdate(boolean enabled, @NotNull UserContext.Role role) {
    }

    public record BaseCreate(@NotBlank @Size(max = 200) String name, @NotNull @Size(max = 2000) String description) {
    }

    public record BaseUpdate(@Positive long version, @NotBlank @Size(max = 200) String name,
                             @NotNull @Size(max = 2000) String description, boolean enabled) {
    }

    public record DocumentUpdate(@Positive int documentVersion, @NotBlank @Size(max = 200) String title,
                                 @NotBlank String text) {
    }

    public record NoteSource(@Positive long knowledgeBaseId, @Positive long documentId, @Positive int documentVersion) {
        /**
         * 通过 HTTP 元素约束后才转成纯 Java 来源契约。
         */
        public SourceDependency toDependency() {
            return new SourceDependency(knowledgeBaseId, documentId, documentVersion);
        }
    }

    public record Note(@Positive long knowledgeBaseId, @NotBlank @Size(max = 200) String title,
                       @NotBlank String content,
                       @NotEmpty @Size(max = 32) List<@NotNull @jakarta.validation.Valid NoteSource> sourceDependencies) {
    }

    public record Decision(boolean approved) {
    }

    public record MemoryCreate(@NotBlank @Size(max = 2000) String content) {
    }

    public record MemoryUpdate(@Positive long version, @NotBlank @Size(max = 2000) String content) {
    }
}
