package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.ContentWorkflow;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 仅服务器配置；创建时快照，后续配置变更不重置已登记的任务。 */
@Component
@ConfigurationProperties(prefix = "lab.content-workflow")
public class ContentWorkflowProperties {
    private int sourceBytes=1000000, pageBytes=6000, readingPackBytes=96000, maximumItems=4096, indexBytes=96000, unitInputBytes=256000, packageOutputBytes=48000;
    private int maximumUnits=256, parallelism=8, turns=1200, attempts=1800, tools=1200, executionSeconds=7200, resultBytes=4000000;
    public ContentWorkflow.Policy snapshot() { return new ContentWorkflow.Policy(sourceBytes,pageBytes,readingPackBytes,maximumItems,indexBytes,unitInputBytes,packageOutputBytes,maximumUnits,parallelism,turns,attempts,tools,executionSeconds,resultBytes); }
    public int getSourceBytes(){return sourceBytes;} public void setSourceBytes(int v){sourceBytes=v;}
    public int getPageBytes(){return pageBytes;} public void setPageBytes(int v){pageBytes=v;}
    public int getReadingPackBytes(){return readingPackBytes;} public void setReadingPackBytes(int v){readingPackBytes=v;}
    public int getPackageOutputBytes(){return packageOutputBytes;} public void setPackageOutputBytes(int v){packageOutputBytes=v;}
    public int getMaximumItems(){return maximumItems;} public void setMaximumItems(int v){maximumItems=v;}
    public int getIndexBytes(){return indexBytes;} public void setIndexBytes(int v){indexBytes=v;}
    public int getUnitInputBytes(){return unitInputBytes;} public void setUnitInputBytes(int v){unitInputBytes=v;}
    public int getMaximumUnits(){return maximumUnits;} public void setMaximumUnits(int v){maximumUnits=v;}
    public int getParallelism(){return parallelism;} public void setParallelism(int v){parallelism=v;}
    public int getTurns(){return turns;} public void setTurns(int v){turns=v;}
    public int getAttempts(){return attempts;} public void setAttempts(int v){attempts=v;}
    public int getTools(){return tools;} public void setTools(int v){tools=v;}
    public int getExecutionSeconds(){return executionSeconds;} public void setExecutionSeconds(int v){executionSeconds=v;}
    public int getResultBytes(){return resultBytes;} public void setResultBytes(int v){resultBytes=v;}
}
