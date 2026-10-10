package com.example.ailab.ai.workflows.support;

import com.example.ailab.ai.model.*;
import com.example.ailab.contract.dto.ContentLimits;
import com.example.ailab.contract.dto.Learning.*;
import java.util.*;
import static com.example.ailab.ai.model.RecordSchema.FieldRule.*;
import static com.example.ailab.ai.model.RecordSchema.Reason.*;

/** 长度单位显式声明；目标身份和证据不可由生成器更改。 */
public final class LearningSchemas {
    private LearningSchemas() { }
    public static RecordSchema<Quiz> quiz(List<Target> targets){return quiz(targets,ContentLimits.current());}
    public static RecordSchema<Quiz> quiz(List<Target> targets,ContentLimits limits) {
        var fields=List.of(utf8("title",limits.title()),utf8("questions[].stem",limits.quizStem()),utf8("questions[].answer",limits.quizAnswer()),utf8("questions[].explanation",limits.quizExplanation()),utf8("questions[].options[]",limits.quizOption()));
        return new RecordSchema<>(Quiz.class,"输出title和questions；questions必须按给定targets顺序完整输出。每题id、type、itemIds与target完全一致。"
                +"SINGLE_CHOICE必须四个不含编号的选项，answer只能为A/B/C/D；SHORT_ANSWER的options为空，answer为参考答案；explanation解释答案依据。",
                fields,value->{
            RecordSchema.require(value.questions().size()==targets.size(),CONTENT_CONSTRAINT,"questions","题目数量必须与给定targets一致");
            for(int i=0;i<Math.min(value.questions().size(),targets.size());i++){
                validateQuestion(value.questions().get(i),targets.get(i),"questions["+i+"].");
            }
        });
    }
    public static void validateQuestion(Question q,Target target,String prefix) {
        RecordSchema.require(q.id().equals(target.id()),CONTENT_CONSTRAINT,prefix+"id",target.id());
        RecordSchema.require(q.type().equals(target.type()),CONTENT_CONSTRAINT,prefix+"type",target.type());
        RecordSchema.require(q.itemIds().equals(target.itemIds()),CONTENT_CONSTRAINT,prefix+"itemIds","与目标事实编号和顺序一致");
        if(q.type().equals("SINGLE_CHOICE")){
            RecordSchema.require(q.options().size()==4,CONTENT_CONSTRAINT,prefix+"options","恰好四个选项");
            RecordSchema.require(Set.of("A","B","C","D").contains(q.answer()),CONTENT_CONSTRAINT,prefix+"answer","A/B/C/D");
        }else RecordSchema.require(q.options().isEmpty(),CONTENT_CONSTRAINT,prefix+"options","简答题选项为空数组");
    }
    public static RecordSchema<Review> review(Set<String> units){return review(units,ContentLimits.current());}
    public static RecordSchema<Review> review(Set<String> units,ContentLimits limits) {
        var fields=new ArrayList<RecordSchema.FieldRule>();
        fields.add(choices("decision",Set.of("ACCEPT","REPAIR")));fields.add(items("issues",32));
        fields.add(choices("issues[].unitId",units));
        fields.add(choices("issues[].code",Set.of("UNSUPPORTED_ANSWER","AMBIGUOUS","DUPLICATE","MISSING_CONTENT","CONFLICT_LOST","BAD_ORGANIZATION")));
        fields.add(utf8("issues[].evidence",3000));
        fields.add(utf8("issues[].suggestion",3000));
        return new RecordSchema<>(Review.class,"输出decision=ACCEPT或REPAIR及issues数组。ACCEPT时issues为空；REPAIR时每项含unitId、code、evidence、suggestion。",fields,value->{
            RecordSchema.require(value.decision().equals("ACCEPT")==value.issues().isEmpty(),CONTENT_CONSTRAINT,"issues","ACCEPT时为空；REPAIR时至少一项问题");
        });
    }
    public static Review withLocalQuizChecks(Quiz quiz,Review review) {
        var issues=new LinkedHashMap<String,Issue>();review.issues().forEach(issue->issues.putIfAbsent(issue.unitId(),issue));
        var stems=new HashSet<String>();
        for(var q:quiz.questions()){
            if(!stems.add(normalize(q.stem())))issues.put(q.id(),new Issue(q.id(),"DUPLICATE","题干与已有题目重复","围绕已有考点生成不同的问题"));
            if(q.options().stream().map(LearningSchemas::normalize).distinct().count()!=q.options().size())
                issues.put(q.id(),new Issue(q.id(),"AMBIGUOUS","选项文本重复","保留唯一正确答案并生成互不重复的选项"));
        }
        return issues.isEmpty()?review:new Review("REPAIR",List.copyOf(issues.values()));
    }
    private static String normalize(String text){return text.replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
}
