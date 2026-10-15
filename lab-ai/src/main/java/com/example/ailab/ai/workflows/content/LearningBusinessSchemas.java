package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.LearningContent.*;
import com.example.ailab.ai.workflows.support.LearningSchemas;
import java.util.*;
import java.util.stream.Collectors;
import static com.example.ailab.ai.model.RecordSchema.*;
import static com.example.ailab.ai.model.RecordSchema.FieldRule.*;
import static com.example.ailab.ai.model.RecordSchema.Reason.*;

final class LearningBusinessSchemas {
    private LearningBusinessSchemas() { }
    static RecordSchema<Directory> directory(int maximum) {
        return directory(maximum,false);
    }
    static RecordSchema<Directory> learningDirectory(){return directory(LearningPlanRules.MAX_CHAPTERS,true);}
    private static RecordSchema<Directory> directory(int maximum,boolean learning) {
        return new RecordSchema<>(Directory.class,(learning?"原文目录不可靠，基于全部主题建立推断学习分组。可以合并跨自然片段的同一学习主题；只组织原文内容，不声称是真实标题。":"基于全范围主题索引提出融合后的输出目录。")+"chapters id=c1起连续，parentId无父章为空。各章有不同内容职责、排除的邻章职责、统一术语；只有确实需要另一章实际写作结果才登记resultDependencies。relatedChapterIds用于相关小章合包。不要按来源文档或索引包划目录。",
                List.of(utf8("title",300),utf8("audience",300),utf8("requirements",1000),utf8("reason",1000),items("chapters",maximum),utf8("chapters[].title",200),utf8("chapters[].purpose",600)),v->{
            require(!v.chapters().isEmpty(),CONTENT_CONSTRAINT);var ids=v.chapters().stream().map(DirectoryChapter::id).collect(Collectors.toSet());
            for(int i=0;i<v.chapters().size();i++) {
                var c=v.chapters().get(i);String path="chapters["+i+"]";
                require(c.id().equals("c"+(i+1)),ID_ORDER_MISMATCH,path+".id","c"+(i+1));
                require(c.parentId().isEmpty()||ids.contains(c.parentId()),UNKNOWN_REFERENCE,path+".parentId","空字符串或已有章节编号");
                require(ids.containsAll(c.resultDependencies()),UNKNOWN_REFERENCE,path+".resultDependencies","已有章节编号");
                require(ids.containsAll(c.relatedChapterIds()),UNKNOWN_REFERENCE,path+".relatedChapterIds","已有章节编号");
            }
            LearningPlanRules.dag(v.chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::resultDependencies)));
            var parents=v.chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::parentId));
            LearningPlanRules.dag(v.chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,c->c.parentId().isEmpty()?List.of():List.of(c.parentId()))));
            for(String id:ids)require(LearningHierarchy.depth(id,parents)<=4,CONTENT_CONSTRAINT);
        });
    }
    static RecordSchema<Assignments> assignments(List<ContentReading.TopicCard> cards,Directory directory) {
        var allowed=cards.stream().map(ContentReading.TopicCard::id).collect(Collectors.toSet());var chapters=directory.chapters().stream().map(DirectoryChapter::id).collect(Collectors.toSet());
        return new RecordSchema<>(Assignments.class,"每张主题卡恰好分配一个主要输出章节，遵循全局目录职责。所有卡片必须分配，不能因主题无关而省略。返回cardId/chapterId/reason。",List.of(items("assignments",cards.size()),utf8("assignments[].reason",300)),v->{
            var seen=new HashSet<String>();
            for(int i=0;i<v.assignments().size();i++) {var a=v.assignments().get(i);String path="assignments["+i+"]";
                require(allowed.contains(a.cardId()),UNKNOWN_REFERENCE,path+".cardId","本批主题卡编号");
                require(chapters.contains(a.chapterId()),UNKNOWN_REFERENCE,path+".chapterId","固定目录中的章节编号");
                require(seen.add(a.cardId()),DUPLICATE_ID,path+".cardId","每张主题卡只能分配一次");
            }
            require(seen.containsAll(allowed),MISSING_COVERAGE,"assignments","覆盖本批全部主题卡");
        });
    }
    static RecordSchema<Relation> relation() {
        return new RecordSchema<>(Relation.class,"读取两组原始证据核验关系。SAME仅适用于知识相同且条件一致；CONFLICT保留不同说法及版本条件；COMPLEMENT仅表示同主题互补；SEPARATE表示职责不同。summary精确概括共同职责，conditions保留全部不同条件，reason给出证据判断依据。",
                List.of(choices("decision",Set.of("SAME","CONFLICT","COMPLEMENT","SEPARATE")),utf8("summary",500),utf8("reason",500),items("conditions",12)),v->require(!v.decision().equals("CONFLICT")||!v.conditions().isEmpty(),CONTENT_CONSTRAINT,"conditions","CONFLICT 必须保留不同条件"));
    }
    public record CandidateRelation(String candidateId,Relation relation) { }
    public record CandidateRelations(List<CandidateRelation> relations) {
        public CandidateRelations { relations=List.copyOf(relations); }
    }
    public record NormalizationGroup(String existingId,String relation,String summary,List<String> itemIds,List<String> conditions) {
        public NormalizationGroup { itemIds=List.copyOf(itemIds);conditions=List.copyOf(conditions); }
    }
    public record NormalizationBatch(List<NormalizationGroup> groups) {
        public NormalizationBatch { groups=List.copyOf(groups); }
    }
    static RecordSchema<NormalizationBatch> normalizationBatch(List<String> ids,Map<String,List<String>> candidates,java.util.function.BiPredicate<String,String> related) {
        return new RecordSchema<>(NormalizationBatch.class,"将本批facts按知识关系归并，每条事实恰好属于一个group，禁止遗漏。"
                +"existingId为空表示新知识，否则必须取该组所有事实candidateIds共同允许的既有知识ID，同一existingId只能出现一次。"
                +"优先归入相同或冲突的既有知识；多个既有候选均匹配时选candidateIds顺序中的第一个，再归并本批新事实。"
                +"每组itemIds按facts顺序排列，各组按首条事实顺序排列。SAME仅表示知识相同且条件一致；CONFLICT保留不同说法和全部版本条件；互补或职责不同必须分别保留。"
                +"新建单事实为SINGLE，多事实或归入既有知识只能SAME/CONFLICT。summary精确概括该组及既有知识的共同职责，conditions保留全部不同条件。",
                List.of(items("groups",ids.size()),choices("groups[].relation",Set.of("SINGLE","SAME","CONFLICT")),utf8("groups[].summary",500),items("groups[].conditions",64)),v->{
            var seen=new HashSet<String>();var existing=new HashSet<String>();int previous=-1;
            for(int groupIndex=0;groupIndex<v.groups().size();groupIndex++) {
                var group=v.groups().get(groupIndex);String path="groups["+groupIndex+"]";
                require(!group.itemIds().isEmpty(),NON_EMPTY,path+".itemIds","至少一个本批事实编号");int last=-1;
                for(String id:group.itemIds()) {
                    int index=ids.indexOf(id);
                    require(index>=0,UNKNOWN_REFERENCE,path+".itemIds","本批事实编号");
                    require(index>last,ID_ORDER_MISMATCH,path+".itemIds","按输入 facts 顺序排列");
                    require(seen.add(id),DUPLICATE_ID,path+".itemIds","每条事实只能属于一个 group");last=index;
                    if(!group.existingId().isEmpty())require(candidates.getOrDefault(id,List.of()).contains(group.existingId()),UNKNOWN_REFERENCE,path+".existingId","该组所有事实共同允许的既有知识编号");
                    else if(!group.itemIds().isEmpty())require(related.test(group.itemIds().get(0),id),RELATION_CONSTRAINT,path+".itemIds","组内事实必须有关联");
                }
                int first=group.itemIds().isEmpty()?-1:ids.indexOf(group.itemIds().get(0));require(first>previous,ID_ORDER_MISMATCH,path+".itemIds","各组按首条事实顺序排列");previous=first;
                if(!group.existingId().isEmpty()) {
                    require(existing.add(group.existingId()),DUPLICATE_ID,path+".existingId","同一既有知识只能出现一次");
                    require(!group.relation().equals("SINGLE"),RELATION_CONSTRAINT,path+".relation","归入既有知识必须为 SAME 或 CONFLICT");
                } else require(group.itemIds().size()==1?group.relation().equals("SINGLE"):!group.relation().equals("SINGLE"),RELATION_CONSTRAINT,path+".relation","新建单事实为 SINGLE，多事实为 SAME 或 CONFLICT");
                require(!group.relation().equals("CONFLICT")||!group.conditions().isEmpty(),NON_EMPTY,path+".conditions","CONFLICT 必须保留版本条件");
            }
            require(seen.containsAll(ids),MISSING_COVERAGE,"groups","本批每条事实必须恰好属于一个 group");
        });
    }
    static RecordSchema<CandidateRelations> candidateRelations(List<String> ids) {
        return new RecordSchema<>(CandidateRelations.class,"对candidate与每个candidates中的existing分别核验原始证据，按输入candidateId顺序返回relations，每项包含candidateId和relation。"
                +relation().instruction(Set.of()),List.of(items("relations",ids.size()),utf8("relations[].relation.summary",500),utf8("relations[].relation.reason",500)),v->{
            require(v.relations().stream().map(CandidateRelation::candidateId).toList().equals(ids),ID_ORDER_MISMATCH,"relations","candidateId 数量和顺序与输入一致");
            for(int i=0;i<v.relations().size();i++) {var value=v.relations().get(i);nested("relations["+i+"].relation",()->relation().validate(ContentJson.encode(value.relation()),Set.of()));}
        });
    }
    static RecordSchema<SectionOutline> sectionOutline() {
        return new RecordSchema<>(SectionOutline.class,"根据本章全部知识索引先确定业务小节职责。id=s1起连续；技术输入批次不是小节边界。给出标题、purpose和500至12000的保守outputBytes。后续逐批知识只分配至这些固定职责。",
                List.of(items("sections",128),utf8("sections[].title",200),utf8("sections[].purpose",600)),v->{require(!v.sections().isEmpty(),CONTENT_CONSTRAINT);for(int i=0;i<v.sections().size();i++){var s=v.sections().get(i);require(s.id().equals("s"+(i+1))&&s.outputBytes()>=500&&s.outputBytes()<=ContentLimits.current().section(),CONTENT_CONSTRAINT);}});
    }
    static RecordSchema<PlanningAdvice> planningAdvice(){return new RecordSchema<>(PlanningAdvice.class,"检查规划索引是否足以制定业务职责；足够返回READY且requests=[]，不够返回CLARIFY并提出至多4个query/question。query必须使用索引已有主题词。directoryPolicy=SOURCE表示原文标题可信，INFER表示缺失或不可靠标题需推断学习分组。",
            List.of(choices("decision",Set.of("READY","CLARIFY")),choices("directoryPolicy",Set.of("SOURCE","INFER")),items("requests",4),utf8("requests[].query",160),utf8("requests[].question",600)),v->require(v.decision().equals("READY")?v.requests().isEmpty():!v.requests().isEmpty()&&v.requests().stream().allMatch(q->!q.query().isBlank()),CONTENT_CONSTRAINT));}
    static RecordSchema<Supplements> supplements(Set<String> ids){return new RecordSchema<>(Supplements.class,"只根据传入固定正文补充指定主题卡的summary、keywords和conditions。保持cardId；不能引入外部知识或改变事实归属。每个指定卡恰好一项。",
            List.of(items("cards",ids.size()),utf8("cards[].summary",600),items("cards[].keywords",12),items("cards[].conditions",12),utf8("cards[].keywords[]",200),utf8("cards[].conditions[]",200)),v->require(v.cards().size()==ids.size()&&v.cards().stream().map(SupplementCard::cardId).collect(Collectors.toSet()).equals(ids),CONTENT_CONSTRAINT));}
    static RecordSchema<KnowledgeAssignments> knowledgeAssignments(List<Knowledge> knowledge,SectionOutline outline) {
        var allowed=knowledge.stream().map(Knowledge::id).collect(Collectors.toSet());var sections=outline.sections().stream().map(SectionOutlineItem::id).collect(Collectors.toSet());
        return new RecordSchema<>(KnowledgeAssignments.class,"每个knowledgeId分配唯一主要sectionId。contextSectionIds只表示需要该知识作为背景的其他小节；不是新的主要职责。必须覆盖本批全部规范知识，不能改变已固定的小节清单。",v->{var seen=new HashSet<String>();for(var a:v.assignments())require(allowed.contains(a.knowledgeId())&&seen.add(a.knowledgeId())&&sections.contains(a.sectionId())&&sections.containsAll(a.contextSectionIds())&&!a.contextSectionIds().contains(a.sectionId()),CONTENT_CONSTRAINT);require(seen.equals(allowed),CONTENT_CONSTRAINT);});
    }
    static RecordSchema<QuizIntent> quizIntent(Set<String> chapters) {
        return new RecordSchema<>(QuizIntent.class,"归纳单文档自测目标与备注。默认coveragePolicy=EVERY_CHAPTER；明确要求重点抽样时FOCUS，明确少量代表性抽样时REPRESENTATIVE。requestedQuestions仅在用户要求精确题数时返回十进制字符串，否则AUTO。focusChapterIds仅引用已提供学习章节。",
                List.of(utf8("title",300),utf8("requirements",1000),utf8("reason",1000),choices("coveragePolicy",Set.of("EVERY_CHAPTER","FOCUS","REPRESENTATIVE"))),v->{require(v.requestedQuestions().equals("AUTO")||v.requestedQuestions().matches("[1-9][0-9]{0,2}"),CONTENT_CONSTRAINT);require(chapters.containsAll(v.focusChapterIds())&&(!v.coveragePolicy().equals("FOCUS")||!v.focusChapterIds().isEmpty()),CONTENT_CONSTRAINT);});
    }
    static RecordSchema<Objectives> objectives(List<Learning.Item> facts,Set<String> types,Set<String> chapters) {
        var ids=facts.stream().map(Learning.Item::id).collect(Collectors.toSet());
        return new RecordSchema<>(Objectives.class,"提取不同可命题目标；认知目的为RECALL/UNDERSTAND/APPLY/COMPARE。每个目标有具体考点、连续原文事实依据、可用题型、1至3的重要性及理由。同一事实编号集合（忽略顺序）与同一cognition只需一个候选；程序会合并重复候选，不能只换题干增加容量。目录/引用/无效正文列入unexaminedItemIds并说明原因。相关章节只作为支持，主归属由程序固定。",
                List.of(items("objectives",128),utf8("objectives[].title",200),utf8("objectives[].reason",500),choices("objectives[].cognition",Set.of("RECALL","UNDERSTAND","APPLY","COMPARE")),optionalUtf8("reason",600)),v->{
            var covered=new HashSet<String>();for(var o:v.objectives()) {require(!o.itemIds().isEmpty()&&ids.containsAll(o.itemIds())&&types.containsAll(o.questionTypes())&&!o.questionTypes().isEmpty()&&o.priority()>=1&&o.priority()<=3&&chapters.containsAll(o.relatedChapterIds()),CONTENT_CONSTRAINT);
                // 候选重复由规划器确定性合并；此处仍核验事实、题型、章节与完整覆盖。
                covered.addAll(o.itemIds());}
            require(ids.containsAll(v.unexaminedItemIds())&&Collections.disjoint(covered,v.unexaminedItemIds()),CONTENT_CONSTRAINT);covered.addAll(v.unexaminedItemIds());require(covered.equals(ids)&& (v.unexaminedItemIds().isEmpty()||!v.reason().isBlank()),CONTENT_CONSTRAINT);
        });
    }
    static RecordSchema<SectionBatch> sectionBatch(List<Part> parts) {
        return new RecordSchema<>(SectionBatch.class,"为本包全部小节片段输出parts；partId和itemIds保持清单顺序。正文只承担owned职责，背景用于理解及衔接。保留冲突各方、条件和引用意义，禁止凭空补充。",List.of(items("parts",parts.size()),utf8("parts[].body",ContentLimits.current().section())),v->{
            require(v.parts().size()==parts.size(),CONTENT_CONSTRAINT,"parts","完整输出本包全部片段");
            for(int i=0;i<Math.min(parts.size(),v.parts().size());i++) {
                require(v.parts().get(i).partId().equals(parts.get(i).id()),ID_ORDER_MISMATCH,"parts["+i+"].partId",parts.get(i).id());
                require(v.parts().get(i).itemIds().equals(parts.get(i).itemIds()),ID_ORDER_MISMATCH,"parts["+i+"].itemIds","与固定清单的事实编号及顺序一致");
            }
        });
    }
    static RecordSchema<QuestionBatch> questions(List<Part> parts,Map<String,Slot> slots) {
        var limits=ContentLimits.current();
        return new RecordSchema<>(QuestionBatch.class,"一次填写全部已分配题位；题id为logicalId，题型和itemIds严格固定。单选四选项且答案为A/B/C/D；简答options为空。题干、正确答案及解析均须有证据，并符合每题认知目的。",List.of(items("questions",parts.size()),utf8("questions[].stem",limits.quizStem()),utf8("questions[].answer",limits.quizAnswer()),utf8("questions[].explanation",limits.quizExplanation()),utf8("questions[].options[]",limits.quizOption())),v->{
            require(v.questions().size()==parts.size(),CONTENT_CONSTRAINT,"questions","按题位清单返回全部题目");for(int i=0;i<Math.min(parts.size(),v.questions().size());i++){var slot=slots.get(parts.get(i).logicalId());var question=v.questions().get(i);nested("questions["+i+"]",()->LearningSchemas.question(new Learning.Target(slot.id(),slot.type(),slot.itemIds())).validate(ContentJson.encode(question),Set.of()));}
        });
    }
    static RecordSchema<GlobalReview> global(Set<String> ids) {
        return new RecordSchema<>(GlobalReview.class,"核对全局目录/蓝图、覆盖、术语、衔接、考点与认知目的的重复、答案和证据。先看摘要；不确定时decision=INSPECT，在inspectedUnitIds给出最多4个需读正文的稳定ID。ACCEPT必须无issues。REPAIR每条问题给出具体unitId、code、evidence和suggestion，只修问题单元。",
                List.of(choices("decision",Set.of("ACCEPT","REPAIR","INSPECT")),items("issues",16),items("inspectedUnitIds",4),utf8("issues[].evidence",600),utf8("issues[].suggestion",600)),v->{
            require(ids.containsAll(v.inspectedUnitIds()),UNKNOWN_REFERENCE,"inspectedUnitIds","已有单元编号");
            for(int i=0;i<v.issues().size();i++)require(ids.contains(v.issues().get(i).unitId()),UNKNOWN_REFERENCE,"issues["+i+"].unitId","已有单元编号");
            require(!v.decision().equals("ACCEPT")||v.issues().isEmpty(),CONTENT_CONSTRAINT,"issues","ACCEPT 时问题列表必须为空");
            require(!v.decision().equals("INSPECT")||!v.inspectedUnitIds().isEmpty(),NON_EMPTY,"inspectedUnitIds","INSPECT 至少选择一个单元");
            require(!v.decision().equals("REPAIR")||!v.issues().isEmpty(),NON_EMPTY,"issues","REPAIR 至少列出一个具体问题");
        });
    }
}
