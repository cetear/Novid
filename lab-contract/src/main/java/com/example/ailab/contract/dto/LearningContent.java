package com.example.ailab.contract.dto;

import java.util.*;

/** 学习业务计划与执行计划分别冻结；调用拆分不改变目录、题位或主要覆盖职责。 */
public final class LearningContent {
    private LearningContent() { }
    public static final int VERSION=4;
    public record Knowledge(String id,String relation,String summary,List<String> sourceItemIds,List<String> conditions) {
        public Knowledge {sourceItemIds=List.copyOf(sourceItemIds);conditions=List.copyOf(conditions);}
    }
    public record DirectoryChapter(String id,String parentId,String title,String purpose,List<String> exclusions,
                                   List<String> resultDependencies,List<String> relatedChapterIds,List<String> terms) {
        public DirectoryChapter {exclusions=List.copyOf(exclusions);resultDependencies=List.copyOf(resultDependencies);relatedChapterIds=List.copyOf(relatedChapterIds);terms=List.copyOf(terms);}
    }
    public record Directory(String title,String audience,String requirements,String reason,List<DirectoryChapter> chapters,List<String> terms) {
        public Directory {chapters=List.copyOf(chapters);terms=List.copyOf(terms);}
    }
    public record Assignment(String cardId,String chapterId,String reason) { }
    public record Assignments(List<Assignment> assignments) {public Assignments {assignments=List.copyOf(assignments);}}
    public record Relation(String decision,String summary,List<String> conditions,String reason) {public Relation {conditions=List.copyOf(conditions);}}
    public record SectionDuty(String id,String chapterId,String title,String purpose,List<String> ownedKnowledgeIds,
                              List<String> contextKnowledgeIds,int outputBytes) {
        public SectionDuty {ownedKnowledgeIds=List.copyOf(ownedKnowledgeIds);contextKnowledgeIds=List.copyOf(contextKnowledgeIds);}
    }
    public record SectionOutlineItem(String id,String title,String purpose,int outputBytes) { }
    public record SectionOutline(List<SectionOutlineItem> sections) { public SectionOutline { sections=List.copyOf(sections); } }
    public record KnowledgeAssignment(String knowledgeId,String sectionId,List<String> contextSectionIds) {
        public KnowledgeAssignment { contextSectionIds=List.copyOf(contextSectionIds); }
    }
    public record KnowledgeAssignments(List<KnowledgeAssignment> assignments) { public KnowledgeAssignments { assignments=List.copyOf(assignments); } }
    public record ClarificationRequest(String query,String question) { }
    public record PlanningAdvice(String decision,String directoryPolicy,List<ClarificationRequest> requests) {public PlanningAdvice {requests=List.copyOf(requests);} }
    public record SupplementCard(String cardId,String summary,List<String> keywords,List<String> conditions) {public SupplementCard {keywords=List.copyOf(keywords);conditions=List.copyOf(conditions);} }
    public record Supplements(List<SupplementCard> cards) { public Supplements {cards=List.copyOf(cards);} }
    public record CompilationPlan(Directory directory,List<Knowledge> knowledge,List<SectionDuty> sections) {
        public CompilationPlan {knowledge=List.copyOf(knowledge);sections=List.copyOf(sections);}
    }
    public record Range(String fragmentId,String sectionId,int startOffset,int endOffset) { }
    public record Chapter(String id,String parentId,int order,String title,String origin,List<Range> ranges,List<String> itemIds) {
        public Chapter {ranges=List.copyOf(ranges);itemIds=List.copyOf(itemIds);}
    }
    public record Objective(String id,String primaryChapterId,String title,String cognition,List<String> questionTypes,List<String> itemIds,
                            int priority,String reason,List<String> relatedChapterIds) {
        public Objective {questionTypes=List.copyOf(questionTypes);itemIds=List.copyOf(itemIds);relatedChapterIds=List.copyOf(relatedChapterIds);}
    }
    public record ObjectiveDraft(String title,String cognition,List<String> questionTypes,List<String> itemIds,int priority,String reason,List<String> relatedChapterIds) {
        public ObjectiveDraft {questionTypes=List.copyOf(questionTypes);itemIds=List.copyOf(itemIds);relatedChapterIds=List.copyOf(relatedChapterIds);}
    }
    public record Objectives(List<ObjectiveDraft> objectives,List<String> unexaminedItemIds,String reason) {
        public Objectives {objectives=List.copyOf(objectives);unexaminedItemIds=List.copyOf(unexaminedItemIds);}
    }
    public record ObjectiveEvidence(List<String> itemIds) {public ObjectiveEvidence {itemIds=List.copyOf(itemIds);}}
    public record QuizIntent(String title,String requirements,String reason,String coveragePolicy,String requestedQuestions,List<String> focusChapterIds) {
        public QuizIntent {focusChapterIds=List.copyOf(focusChapterIds);}
    }
    public record Quota(String chapterId,int minimum,int maximum,int weight,int allocated,String reason) { }
    public record Slot(String id,String primaryChapterId,String objectiveId,String cognition,String type,List<String> itemIds,List<String> relatedChapterIds) {
        public Slot {itemIds=List.copyOf(itemIds);relatedChapterIds=List.copyOf(relatedChapterIds);}
    }
    public record ReviewPair(String leftId,String rightId) {
        public String key(){return leftId+"_"+rightId;}
    }
    public record Uncovered(String kind,String id,String reason) { }
    public record Blueprint(QuizIntent intent,String difficulty,List<Chapter> chapters,List<Objective> objectives,List<Quota> quotas,List<Slot> slots,List<Uncovered> uncovered) {
        public Blueprint {chapters=List.copyOf(chapters);objectives=List.copyOf(objectives);quotas=List.copyOf(quotas);slots=List.copyOf(slots);uncovered=List.copyOf(uncovered);}
    }
    public record Part(String id,String logicalId,String chapterId,String kind,String purpose,List<String> ownedKnowledgeIds,List<String> contextKnowledgeIds,List<String> itemIds,int outputBytes) {
        public Part {ownedKnowledgeIds=List.copyOf(ownedKnowledgeIds);contextKnowledgeIds=List.copyOf(contextKnowledgeIds);itemIds=List.copyOf(itemIds);}
    }
    public record WorkPackage(String id,String parentId,int depth,List<String> partIds,List<String> dependencies,int inputBytes,int outputBytes) {
        public WorkPackage {partIds=List.copyOf(partIds);dependencies=List.copyOf(dependencies);}
    }
    public record ExecutionPlan(List<Part> parts,List<WorkPackage> packages,int maximumPackages,int maximumSplitDepth) {
        public ExecutionPlan {parts=List.copyOf(parts);packages=List.copyOf(packages);}
    }
    public record Plan(int version,String taskType,String sourceHash,CompilationPlan compilation,Blueprint quiz,ExecutionPlan execution,
                       ContentWorkflow.Counts counts,List<String> requiredNodes,int estimatedTurns,int estimatedAttempts,int estimatedOutputBytes) implements ContentWorkflow.ContentPlan {
        public Plan {requiredNodes=List.copyOf(requiredNodes);}
        public String title(){return compilation!=null?compilation.directory().title():quiz.intent().title();}
        public String requirements(){return compilation!=null?compilation.directory().requirements():quiz.intent().requirements();}
    }
    public record PackageStatus(WorkPackage workPackage,List<Part> parts,String status,int attempts,String resultHash,String errorCode,List<String> children,int acceptedParts) {
        public PackageStatus {parts=List.copyOf(parts);children=List.copyOf(children);}
    }
    public record SectionPart(String partId,String body,List<String> itemIds) {public SectionPart {itemIds=List.copyOf(itemIds);}}
    public record SectionBatch(List<SectionPart> parts) {public SectionBatch {parts=List.copyOf(parts);}}
    public record QuestionBatch(List<Learning.Question> questions) {public QuestionBatch {questions=List.copyOf(questions);}}
    public record Coverage(String id,String kind,int available,int selected,List<String> relatedIds,String reason) {public Coverage {relatedIds=List.copyOf(relatedIds);}}
    public record GlobalReview(String decision,List<Learning.Issue> issues,List<String> inspectedUnitIds) {
        public GlobalReview {issues=List.copyOf(issues);inspectedUnitIds=List.copyOf(inspectedUnitIds);}
    }
}
