package com.example.ailab.contract.dto;

/** 资料工作流统一内容限制，长度均为UTF-8字节。 */
public record ContentLimits(int summaryTitle, int summary, int factCategory, int factContent, int factQuote,
                            int title, int purpose, int requirements, int reason, int imagePrompt,
                            int section, int question, int slide, int slideText, int slideNotes,
                            int quizStem, int quizAnswer, int quizExplanation, int quizOption) {
    private static final ContentLimits CURRENT = new ContentLimits(600,1800,400,2000,1200,600,1200,3200,2000,1600,12000,8000,8000,6000,6000,4800,6400,8000,2400);
    public static ContentLimits current() { return CURRENT; }
}
