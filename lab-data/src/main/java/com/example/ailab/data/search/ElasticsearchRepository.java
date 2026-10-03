package com.example.ailab.data.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.repository.*;
import org.springframework.stereotype.Repository;
import jakarta.annotation.PreDestroy;

import java.io.StringReader;
import java.util.*;

/**
 * 官方 ES 8.15 RestClient + Jackson 2，BM25/kNN 两路范围预过滤，MySQL 负责最终授权。
 */
@Repository
public class ElasticsearchRepository implements KnowledgeIndexPort, KnowledgeSearchPort {
    private final SearchProperties config;
    private final DocumentContextRepository context;
    private final SqlSupport sql;
    private final ElasticsearchClient client;
    private final RestClientTransport transport;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 未启用搜索时不创建连接，资料 CRUD 可独立运行。
     */
    public ElasticsearchRepository(SearchProperties config, DocumentContextRepository context, SqlSupport sql) {
        this.config = config;
        this.context = context;
        this.sql = sql;
        if (config.enabled()) {
            transport = new RestClientTransport(ElasticsearchClientFactory.create(config), new JacksonJsonpMapper(json));
            client = new ElasticsearchClient(transport);
        } else {
            transport = null;
            client = null;
        }
    }

    /**
     * 索引初始化仅由明确运维命令触发，不在启动时销毁重建。
     */
    public void initialize() {
        enabled();
        try {
            if (client.indices().exists(e -> e.index(config.index())).value()) return;
            Map<String, Object> fields = new LinkedHashMap<>();
            for (String field : List.of("knowledgeBaseId", "ownerUserId", "documentId", "documentVersion", "processingRevision"))
                fields.put(field, Map.of("type", "long"));
            for (String field : List.of("chunkId", "chunkHash", "embeddingModelVersion", "sectionId", "parentId"))
                fields.put(field, Map.of("type", "keyword"));
            fields.put("embeddingText", Map.of("type", "text", "analyzer", "standard"));
            fields.put("enabled", Map.of("type", "boolean"));
            fields.put("deleted", Map.of("type", "boolean"));
            fields.put("vector", Map.of("type", "dense_vector", "dims", config.dimensions(), "index", true, "similarity", "cosine"));
            String body = encode(Map.of("mappings", Map.of("dynamic", "strict", "properties", fields), "settings", Map.of("number_of_shards", 1, "number_of_replicas", 0)));
            client.indices().create(i -> i.index(config.index()).withJson(new StringReader(body)));
        } catch (Exception e) {
            throw unavailable();
        }
    }

    /**
     * 批量 ID 包含版本和处理代次；批量结束后一次 refresh 验证可见性。
     */
    public void index(List<IndexedChunk> chunks) {
        enabled();
        try {
            for (int offset = 0; offset < chunks.size(); offset += 32) {
                var operations = new ArrayList<BulkOperation>();
                for (var c : chunks.subList(offset, Math.min(offset + 32, chunks.size()))) {
                    compatible(c.vector(), c.embeddingModelVersion());
                    operations.add(BulkOperation.of(o -> o.index(i -> i.index(config.index()).id(c.chunk().chunkId()).document(source(c)))));
                }
                var result = client.bulk(b -> b.operations(operations));
                if (result.errors()) throw new LabException("SEARCH_UNAVAILABLE", "索引批量存在失败项，未激活批次");
            }
            client.indices().refresh(r -> r.index(config.index()));
        } catch (LabException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    /**
     * 按全集 ID 在搜索路径核对 hash/模型/维度，不以 bulk 成功数量代替完整性。
     */
    public void verify(List<IndexedChunk> chunks) {
        enabled();
        try {
            for (int offset = 0; offset < chunks.size(); offset += 32) {
                var expected = chunks.subList(offset, Math.min(offset + 32, chunks.size()));
                String body = encode(Map.of("size", 32, "query", Map.of("ids", Map.of("values", expected.stream().map(c -> c.chunk().chunkId()).toList()))));
                var found = client.search(s -> s.index(config.index()).withJson(new StringReader(body)), JsonData.class).hits().hits();
                if (found.size() != expected.size()) throw new LabException("INDEX_NOT_READY", "索引搜索可见性不完整");
                var byId = new HashMap<String, IndexedChunk>();
                expected.forEach(c -> byId.put(c.chunk().chunkId(), c));
                for (var hit : found) {
                    var c = byId.get(hit.id());
                    var data = hit.source().toJson();
                    if (c == null || !data.asJsonObject().getString("chunkHash").equals(c.chunk().chunkHash()) || !data.asJsonObject().getString("embeddingModelVersion").equals(c.embeddingModelVersion()) || data.asJsonObject().getJsonArray("vector").size() != config.dimensions())
                        throw new LabException("INDEX_NOT_READY", "索引项完整性不匹配");
                }
            }
        } catch (LabException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    /**
     * 每次最多删 256 项并刷新；只有再次 count 确认零剩余才返回完成。
     */
    public boolean cleanup(IndexCleanupLease lease) {
        enabled();
        try {
            String body = encode(Map.of("query", cleanupQuery(lease)));
            var result = client.deleteByQuery(r -> r.index(config.index()).maxDocs(256L).refresh(true).conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed).withJson(new StringReader(body)));
            // 超时、冲突或分片失败不能作为删除完成事实。
            if (Boolean.TRUE.equals(result.timedOut()) || !result.failures().isEmpty() || result.versionConflicts() != 0)
                throw unavailable();
            return client.count(r -> r.index(config.index()).withJson(new StringReader(body))).count() == 0;
        } catch (LabException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    /**
     * 版本边界固定：删除文档不越过其删除版本，重处理只清严格旧代次。
     */
    private Map<String, Object> cleanupQuery(IndexCleanupLease lease) {
        if (lease.eventType().equals("DELETE_BASE"))
            return Map.of("term", Map.of("knowledgeBaseId", lease.resourceId()));
        var filters = new ArrayList<Object>();
        filters.add(Map.of("term", Map.of("documentId", lease.resourceId())));
        if (lease.eventType().equals("DELETE_DOCUMENT"))
            filters.add(Map.of("range", Map.of("documentVersion", Map.of("lte", lease.resourceVersion()))));
        else if (lease.eventType().equals("PRUNE_DOCUMENT"))
            filters.add(Map.of("bool", Map.of("minimum_should_match", 1, "should", List.of(
                    Map.of("range", Map.of("documentVersion", Map.of("lt", lease.documentVersion()))),
                    Map.of("bool", Map.of("filter", List.of(Map.of("term", Map.of("documentVersion", lease.documentVersion())), Map.of("range", Map.of("processingRevision", Map.of("lt", lease.processingRevision()))))))))));
        else throw LabException.invalid("未知索引清理事件");
        return Map.of("bool", Map.of("filter", filters));
    }

    /**
     * 两路召回相同 scope，RRF 按排名融合，不把分数冒充真实模型重排。
     */
    public List<ChunkCandidate> search(AuthorizedKnowledgeScope scope, String query, List<Float> vector, String version) {
        enabled();
        sql.actor(scope.actor(), false);
        compatible(vector, version);
        var filters = filters(scope);
        if (scope.mode() == ScopeRequest.Mode.SELECTED && scope.knowledgeBaseIds().isEmpty()) return List.of();
        try {
            var bool = Map.of("bool", Map.of("filter", filters, "must", List.of(Map.of("match", Map.of("embeddingText", query)))));
            var lexical = hits(Map.of("size", 20, "query", bool));
            var dense = hits(Map.of("size", 20, "knn", Map.of("field", "vector", "query_vector", vector, "k", 20, "num_candidates", 100, "filter", Map.of("bool", Map.of("filter", filters)))));
            var scores = new HashMap<String, Double>();
            var candidates = new HashMap<String, ChunkCandidate>();
            merge(lexical, scores, candidates);
            merge(dense, scores, candidates);
            // 保留至多 40 个有界候选，MySQL 过滤后才选择六个合法种子。
            return scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).map(e -> {
                var c = candidates.get(e.getKey());
                return new ChunkCandidate(c.documentId(), c.documentVersion(), c.processingRevision(), c.chunkId(), e.getValue());
            }).toList();
        } catch (LabException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    /**
     * 扩展只读取 SQL 复核过的当前资料与真实邻接关系。
     */
    public List<EvidenceBundle> expand(AuthorizedKnowledgeScope scope, List<ChunkCandidate> candidates, int bytes) {
        return context.expand(scope, candidates, bytes);
    }

    /**
     * 生成严格 ES 预过滤条件，普通用户永远附加 owner。
     */
    private List<Object> filters(AuthorizedKnowledgeScope scope) {
        var result = new ArrayList<Object>();
        result.add(Map.of("term", Map.of("enabled", true)));
        result.add(Map.of("term", Map.of("deleted", false)));
        result.add(Map.of("term", Map.of("embeddingModelVersion", config.embeddingModelVersion())));
        if (scope.actor().role() != UserContext.Role.ADMIN || scope.mode() == ScopeRequest.Mode.SELF)
            result.add(Map.of("term", Map.of("ownerUserId", scope.actor().userId())));
        if (scope.mode() == ScopeRequest.Mode.SELECTED)
            result.add(Map.of("terms", Map.of("knowledgeBaseId", scope.knowledgeBaseIds())));
        if (scope.ownerUserId() != null) result.add(Map.of("term", Map.of("ownerUserId", scope.ownerUserId())));
        return result;
    }

    /**
     * JSON Body 经官方客户端解析，不接收模型任意查询 DSL。
     */
    private List<ChunkCandidate> hits(Map<String, Object> body) throws Exception {
        String encoded = encode(body);
        var result = client.search(s -> s.index(config.index()).withJson(new StringReader(encoded)), JsonData.class);
        return result.hits().hits().stream().map(h -> {
            var d = h.source().toJson().asJsonObject();
            return new ChunkCandidate(d.getJsonNumber("documentId").longValue(), d.getInt("documentVersion"), d.getJsonNumber("processingRevision").longValue(), h.id(), h.score() == null ? 0 : h.score());
        }).toList();
    }

    /**
     * RRF 分母固定，重复候选只累加排名权重。
     */
    private void merge(List<ChunkCandidate> hits, Map<String, Double> scores, Map<String, ChunkCandidate> candidates) {
        for (int i = 0; i < hits.size(); i++) {
            var c = hits.get(i);
            scores.merge(c.chunkId(), 1.0 / (60 + i + 1), Double::sum);
            candidates.put(c.chunkId(), c);
        }
    }

    /**
     * 向量空间校验包含模型版本，不能仅按相同维度切备用。
     */
    private void compatible(List<Float> vector, String version) {
        if (!config.embeddingModelVersion().equals(version) || vector.size() != config.dimensions() || vector.stream().anyMatch(v -> v == null || !Float.isFinite(v)))
            throw new LabException("MODEL_CAPABILITY_MISMATCH", "向量空间不匹配");
    }

    /**
     * ES 只保存小片，父段正文从 MySQL 受控读取。
     */
    private Map<String, Object> source(IndexedChunk c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("knowledgeBaseId", c.knowledgeBaseId());
        m.put("ownerUserId", c.ownerUserId());
        m.put("documentId", c.documentId());
        m.put("documentVersion", c.documentVersion());
        m.put("processingRevision", c.processingRevision());
        m.put("chunkId", c.chunk().chunkId());
        m.put("chunkHash", c.chunk().chunkHash());
        m.put("sectionId", c.chunk().sectionId());
        m.put("parentId", c.chunk().contextParentId());
        m.put("embeddingText", c.chunk().embeddingText());
        m.put("vector", c.vector());
        m.put("embeddingModelVersion", c.embeddingModelVersion());
        m.put("enabled", true);
        m.put("deleted", false);
        return m;
    }

    /**
     * 服务未配置明确失败，不能偷偷返回 Mock 搜索结果。
     */
    private void enabled() {
        if (client == null) throw unavailable();
    }

    /**
     * 可理解错误不泄露供应商地址或内部异常。
     */
    private LabException unavailable() {
        return new LabException("SEARCH_UNAVAILABLE", "搜索服务未启用或暂不可用");
    }

    /**
     * 序列化固定服务器结构。
     */
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 生命周期关闭网络资源。
     */
    @PreDestroy
    public void close() throws Exception {
        if (transport != null) transport.close();
    }
}
