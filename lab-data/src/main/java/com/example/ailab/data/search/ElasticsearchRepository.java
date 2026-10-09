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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.elasticsearch.client.RestClient;
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
    private final boolean ownsClient;
    private final QueryCandidateCache queryCache = new QueryCandidateCache();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 未启用搜索时不创建连接，资料 CRUD 可独立运行。
     */
    public ElasticsearchRepository(SearchProperties config, DocumentContextRepository context, SqlSupport sql) {
        this(config, context, sql, config.enabled() ? ElasticsearchClientFactory.create(config) : null, true);
    }

    /** 正式装配复用健康检查同一个客户端，不自行创建第二条连接配置。 */
    @Autowired
    public ElasticsearchRepository(SearchProperties config, DocumentContextRepository context, SqlSupport sql,
                                   ObjectProvider<RestClient> clients) {
        this(config, context, sql, clients.getIfAvailable(), false);
    }

    /** 显式区分独立诊断客户端与 Spring 管理客户端的生命周期。 */
    private ElasticsearchRepository(SearchProperties config, DocumentContextRepository context, SqlSupport sql,
                                    RestClient restClient, boolean ownsClient) {
        this.config = config;
        this.context = context;
        this.sql = sql;
        this.ownsClient = ownsClient;
        if (config.enabled()) {
            if (restClient == null) throw new IllegalStateException("已启用搜索但未装配共享 ES 客户端");
            transport = new RestClientTransport(restClient, new JacksonJsonpMapper(json));
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
            throw unavailable(e);
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
            throw unavailable(e);
        }
    }

    /** 验证单批全部在搜索路径可见，部分结果不能冒称成功。 */
    public void verify(List<IndexedChunk> chunks) {
        for(int offset=0;offset<chunks.size();offset+=32) {
            var batch=chunks.subList(offset,Math.min(offset+32,chunks.size()));
            if(present(batch).size()!=batch.size()) throw new LabException("INDEX_NOT_READY","索引搜索可见性或完整性不匹配");
        }
    }

    /** 有界搜索核验，身份／版本／代次／内容／模型及有限向量值均匹配才复用。 */
    public Set<String> present(List<IndexedChunk> chunks) {
        enabled();
        if(chunks.isEmpty()) return Set.of();
        if(chunks.size()>32) throw LabException.invalid("索引核验最多32项");
        try {
            String body=encode(Map.of("size",32,"query",Map.of("ids",Map.of("values",chunks.stream().map(c->c.chunk().chunkId()).toList()))));
            var response=client.search(s->s.index(config.index()).withJson(new StringReader(body)),JsonData.class);
            if(response.timedOut() || response.shards().failed().intValue()>0) throw unavailable();
            var expected=new HashMap<String,IndexedChunk>();chunks.forEach(c->expected.put(c.chunk().chunkId(),c));
            var found=new HashSet<String>();
            for(var hit:response.hits().hits()) {
                var c=expected.get(hit.id());if(c==null || hit.source()==null) continue;
                compatible(c.vector(),c.embeddingModelVersion());
                var d=hit.source().toJson().asJsonObject();
                if(d.getJsonNumber("documentId").longValue()!=c.documentId() || d.getInt("documentVersion")!=c.documentVersion()
                        || d.getJsonNumber("processingRevision").longValue()!=c.processingRevision()
                        || d.getJsonNumber("knowledgeBaseId").longValue()!=c.knowledgeBaseId() || d.getJsonNumber("ownerUserId").longValue()!=c.ownerUserId()
                        || !d.getString("chunkId").equals(c.chunk().chunkId()) || !d.getString("chunkHash").equals(c.chunk().chunkHash())
                        || !d.getString("embeddingText").equals(c.chunk().embeddingText()) || !d.getString("sectionId").equals(c.chunk().sectionId())
                        || !d.getString("parentId").equals(c.chunk().contextParentId()) || !d.getString("embeddingModelVersion").equals(c.embeddingModelVersion())
                        || !d.getBoolean("enabled") || d.getBoolean("deleted")) continue;
                var vector=d.getJsonArray("vector");if(vector.size()!=config.dimensions()) continue;
                boolean valid=true;
                // ES序列化保持float数值；核对完整向量，防止同维度损坏项被计入成功。
                for(int i=0;i<vector.size();i++) if(!Float.isFinite(vector.getJsonNumber(i).numberValue().floatValue())
                        || Float.compare(vector.getJsonNumber(i).numberValue().floatValue(),c.vector().get(i))!=0) { valid=false;break; }
                if(valid) found.add(hit.id());
            }
            return Set.copyOf(found);
        } catch(LabException e){throw e;}
        catch(Exception e){throw unavailable(e);}
    }

    /** 固定代次全集必须恰好匹配，count分片失败亦不能激活。 */
    public void verifyGeneration(long id,int version,long revision,int expected) {
        enabled();
        try {
            String body=encode(Map.of("query",Map.of("bool",Map.of("filter",List.of(
                    Map.of("term",Map.of("documentId",id)),Map.of("term",Map.of("documentVersion",version)),Map.of("term",Map.of("processingRevision",revision)))))));
            var response=client.count(c->c.index(config.index()).withJson(new StringReader(body)));
            if(response.shards().failed().intValue()>0 || response.count()!=expected) throw new LabException("INDEX_NOT_READY","固定代次全集数量不匹配");
        } catch(LabException e){throw e;}
        catch(Exception e){throw unavailable(e);}
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
            var remaining = client.count(r -> r.index(config.index()).withJson(new StringReader(body)));
            if (remaining.shards().failed().intValue() > 0) throw unavailable();
            return remaining.count() == 0;
        } catch (LabException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
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
        var parameters = new com.example.ailab.data.persistence.po.SqlParameters();
        sql.scope(scope, parameters);
        long epoch = sql.knowledgeEpoch();
        var key = new QueryCandidateCache.Key(scope.actor().userId(), scope.actor().role().name(),
                scope.actor().permissionVersion(), scope.mode()+":"+scope.knowledgeBaseIds()+":"+scope.ownerUserId()+":"+sql.cacheScopeState(scope),
                epoch, SqlSupport.hash(query), SqlSupport.hash(vector.toString()),
                "parser-split-active-epoch:"+context.contextPolicy(), "retrieval-no-prompt-v1",
                config.index()+":"+version+":rrf60-v1");
        var cached = queryCache.get(key);
        if (cached != null) return checkedCandidates(scope, epoch, cached, "SEARCH_CACHE_HIT");
        try {
            var bool = Map.of("bool", Map.of("filter", filters, "must", List.of(Map.of("match", Map.of("embeddingText", query)))));
            int perRoute = context.contextPolicy().retrievalPerRoute();
            var lexical = hits(Map.of("size", perRoute, "query", bool));
            var dense = hits(Map.of("size", perRoute, "knn", Map.of("field", "vector", "query_vector", vector, "k", perRoute, "num_candidates", perRoute * 5, "filter", Map.of("bool", Map.of("filter", filters)))));
            var scores = new HashMap<String, Double>();
            var candidates = new HashMap<String, ChunkCandidate>();
            merge(lexical, scores, candidates);
            merge(dense, scores, candidates);
            // 保留至多 40 个有界候选，MySQL 过滤后才选择六个合法种子。
            var result = scores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).map(e -> {
                var c = candidates.get(e.getKey());
                return new ChunkCandidate(c.documentId(), c.documentVersion(), c.processingRevision(), c.chunkId(), e.getValue());
            }).toList();
            checkedCandidates(scope, epoch, result, "SEARCH");
            queryCache.put(key, result);
            return result;
        } catch (LabException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable(e);
        }
    }

    /** 召回结束重核身份和纪元；变化则拒绝本次结果，命中也可靠审计。 */
    private List<ChunkCandidate> checkedCandidates(AuthorizedKnowledgeScope scope, long epoch,
                                                 List<ChunkCandidate> candidates, String action) {
        sql.actor(scope.actor(), false);
        if (sql.knowledgeEpoch() != epoch) throw new LabException("CONTEXT_VERSION_CONFLICT", "资料已变化，请重新检索");
        sql.audit(scope, action, null, candidates.size(), candidates.stream().map(ChunkCandidate::documentId).distinct().toList());
        return candidates;
    }

    /** 管理聚合只读取单实例缓存计数，键和候选不暴露。 */
    public long[] cacheStatistics() { return queryCache.statistics(); }

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
        if (result.timedOut() || result.shards().failed().intValue() > 0) throw unavailable();
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
    private LabException unavailable(Exception failure) {
        org.slf4j.LoggerFactory.getLogger(ElasticsearchRepository.class).error("event=search.failed code=SEARCH_UNAVAILABLE",com.example.ailab.contract.error.DiagnosticFailure.sanitized(failure));
        return unavailable();
    }

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
     * 独立诊断实例自行释放资源；正式共享客户端由 Spring 统一关闭。
     */
    @PreDestroy
    public void close() throws Exception {
        if (transport != null && ownsClient) transport.close();
    }
}
