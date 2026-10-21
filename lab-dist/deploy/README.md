# Novid Linux 发布包

需要 Java 17 和已准备好的 MySQL 8.4；启用搜索时还需要 Elasticsearch。

## 首次启动

1. 将 dist.zip 解压，将 dist 目录部署到 /opt/novid。
2. 复制 config/.env.example 为 config/.env，填写数据库、模型和其他运行配置，执行 chmod 600 config/.env。
3. 运行 sh bin/start.sh；按 Ctrl+C 停止前台服务。
4. 使用 curl --fail http://127.0.0.1:8080/actuator/health 检查健康状态，端口与配置一致。

application.yml 和 log4j2-spring.xml 在 config/ 下，可直接修改；环境变量和启动参数继续拥有更高优先级。config/.env 按字面填写，不加引号，不执行 source。显式填写的 .env 值会覆盖 YAML 占位符的默认值。

## 后台运行

以有 sudo 权限的账户创建 novid 服务用户，并授予其发布目录读取权限以及 var/ 的写入权限。将 novid.service 安装到 /etc/systemd/system/novid.service，然后运行：

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now novid
sudo systemctl status novid --no-pager
sudo journalctl -u novid -n 100 --no-pager
```

服务默认部署路径为 /opt/novid，安装到其他位置时同步修改服务文件的 WorkingDirectory 和 ExecStart。非系统 PATH 下的 Java 可以通过服务文件的 Environment=JAVA_HOME=/实际JDK目录 指定。

## 更新

重新解压发布包到临时目录。停止服务、备份现有 JAR，然后替换 novid.jar；有脚本或配置变更时，比较并合并 bin/、config/ 和服务文件的更新。保留已填写的 config/.env 和 var/，不要用整包解压覆盖现有配置。启动服务并检查日志及健康接口。

Flyway 在启动时执行数据库迁移，更新前按需备份数据库；数据库已执行不兼容迁移时，仅恢复旧 JAR 不足以回退。独立前端需要单独部署。
