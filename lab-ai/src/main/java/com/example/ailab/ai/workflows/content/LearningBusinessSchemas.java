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
            for(int i=0;i<v.chapters().size();i++){var c=v.chapters().get(i);require(c.id().equals("c"+(i+1))&&(c.parentId().isEmpty()||ids.contains(c.parentId()))&&ids.containsAll(c.resultDependencies())&&ids.containsAll(c.relatedChapterIds()),CONTENT_CONSTRAINT);}
            LearningPlanRules.dag(v.chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::resultDependencies)));
            var parents=v.chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::parentId));
            LearningPlanRules.dag(v.chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,c->c.parentId().isEmpty()?List.of():List.of(c.parentId()))));
            for(String id:ids)require(LearningHierarchy.depth(id,parents)<=4,CONTENT_CONSTRAINT);
        });
    }
    static RecordSchema<Assignments> assignments(List<ContentReading.TopicCard> cards,Directory directory) {
        var allowed=cards.stream().map(ContentReading.TopicCard::id).collect(Collectors.toSet());var chapters=directory.chapters().stream().map(DirectoryChapter::id).collect(Collectors.toSet());
        return new RecordSchema<>(Assignments.class,"每张主题卡恰好分配一个主要输出章节，遵循全局目录职责。所有卡片必须分配，不能因主题无关而省略。返回cardId/chapterId/reason。",List.of(items("assignments",cards.size()),utf8("assignments[].reason",300)),v->{
            var seen=new HashSet<String>();for(var a:v.assignments())require(allowed.contains(a.cardId())&&chapters.contains(a.chapterId())&&seen.add(a.cardId()),CONTENT_CONSTRAINT);require(seen.equals(allowed),CONTENT_CONSTRAINT);
        });
    }
    static RecordSchema<Relation> relation() {
        return new RecordSchema<>(Relation.class,"读取两组原始证据核验关系。SAME仅适用于知识相同且条件一致；CONFLICT保留不同说法及版本条件；COMPLEMENT仅表示同主题互补；SEPARATE表示职责不同。summary精确概括共同职责，conditions保留全部不同条件，reason给出证据判断依据。",
                List.of(choices("decision",Set.of("SAME","CONFLICT","COMPLEMENT","SEPARATE")),utf8("summary",500),utf8("reason",500),items("conditions",12)),v->require(!v.decision().equals("CONFLICT")||!v.conditions().isEmpty(),CONTENT_CONSTRAINT));
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
        return new RecordSchema<>(Objectives.class,"提取不同可命题目标；认知目的为RECALL/UNDERSTAND/APPLY/COMPARE。每个目标有具体考点、连续原文事实依据、可用题型、1至3的重要性及理由。不能只换题干增加容量；目录/引用/无效正文列入unexaminedItemIds并说明原因。相关章节只作为支持，主归属由程序固定。",
                List.of(items("objectives",128),utf8("objectives[].title",200),utf8("objectives[].reason",500),choices("objectives[].cognition",Set.of("RECALL","UNDERSTAND","APPLY","COMPARE")),optionalUtf8("reason",600)),v->{
            var covered=new HashSet<String>();var unique=new HashSet<String>();for(var o:v.objectives()) {require(!o.itemIds().isEmpty()&&ids.containsAll(o.itemIds())&&types.containsAll(o.questionTypes())&&!o.questionTypes().isEmpty()&&o.priority()>=1&&o.priority()<=3&&chapters.containsAll(o.relatedChapterIds()),CONTENT_CONSTRAINT);
                require(unique.add(new TreeSet<>(o.itemIds())+"|"+o.cognition()),CONTENT_CONSTRAINT);covered.addAll(o.itemIds());}
            require(ids.containsAll(v.unexaminedItemIds())&&Collections.disjoint(covered,v.unexaminedItemIds()),CONTENT_CONSTRAINT);covered.addAll(v.unexaminedItemIds());require(covered.equals(ids)&& (v.unexaminedItemIds().isEmpty()||!v.reason().isBlank()),CONTENT_CONSTRAINT);
        });
    }
    static RecordSchema<SectionBatch> sectionBatch(List<Part> parts) {
        return new RecordSchema<>(SectionBatch.class,"为本包全部小节片段输出parts；partId和itemIds保持清单顺序。正文只承担owned职责，背景用于理解及衔接。保留冲突各方、条件和引用意义，禁止凭空补充。",List.of(items("parts",parts.size()),utf8("parts[].body",ContentLimits.current().section())),v->{
            require(v.parts().size()==parts.size(),CONTENT_CONSTRAINT);for(int i=0;i<Math.min(parts.size(),v.parts().size());i++)require(v.parts().get(i).partId().equals(parts.get(i).id())&&v.parts().get(i).itemIds().equals(parts.get(i).itemIds()),CONTENT_CONSTRAINT);
        });
    }
    static RecordSchema<QuestionBatch> questions(List<Part> parts,Map<String,Slot> slots) {
        var limits=ContentLimits.current();
        return new RecordSchema<>(QuestionBatch.class,"一次填写全部已分配题位；题id为logicalId，题型和itemIds严格固定。单选四选项且答案为A/B/C/D；简答options为空。题干、正确答案及解析均须有证据，并符合每题认知目的。",List.of(items("questions",parts.size()),utf8("questions[].stem",limits.quizStem()),utf8("questions[].answer",limits.quizAnswer()),utf8("questions[].explanation",limits.quizExplanation()),utf8("questions[].options[]",limits.quizOption())),v->{
            require(v.questions().size()==parts.size(),CONTENT_CONSTRAINT);for(int i=0;i<Math.min(parts.size(),v.questions().size());i++){var slot=slots.get(parts.get(i).logicalId());LearningSchemas.question(new Learning.Target(slot.id(),slot.type(),slot.itemIds())).validate(ContentJson.encode(v.questions().get(i)),Set.of());}
        });
    }
    static RecordSchema<GlobalReview> global(Set<String> ids) {
        return new RecordSchema<>(GlobalReview.class,"核对全局目录/蓝图、覆盖、术语、衔接、考点与认知目的的重复、答案和证据。先看摘要；不确定时decision=INSPECT，在inspectedUnitIds给出最多4个需读正文的稳定ID。ACCEPT必须无issues。REPAIR每条问题给出具体unitId、code、evidence和suggestion，只修问题单元。",
                List.of(choices("decision",Set.of("ACCEPT","REPAIR","INSPECT")),items("issues",16),items("inspectedUnitIds",4),utf8("issues[].evidence",600),utf8("issues[].suggestion",600)),v->{
            require(ids.containsAll(v.inspectedUnitIds())&&v.issues().stream().allMatch(i->ids.contains(i.unitId())),CONTENT_CONSTRAINT);
            require(!v.decision().equals("ACCEPT")||v.issues().isEmpty(),CONTENT_CONSTRAINT);require(!v.decision().equals("INSPECT")||!v.inspectedUnitIds().isEmpty(),CONTENT_CONSTRAINT);require(!v.decision().equals("REPAIR")||!v.issues().isEmpty(),CONTENT_CONSTRAINT);
        });
    }
}
