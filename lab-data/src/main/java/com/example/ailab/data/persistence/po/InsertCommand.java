package com.example.ailab.data.persistence.po;

/** MyBatis 在同一语句／连接上回填自增主键。 */
public final class InsertCommand {
    private final Object[] args;
    private Long id;
    public InsertCommand(Object... args) { this.args = args; }
    public Object[] getArgs() { return args; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
