# LxMcFoundation Maven Central 发布规范

> 文档编号：2
>
> 最后更新：2026-08-13

## 1. 公共坐标

1. Maven Central 命名空间固定为 `io.github.lxcraftcm`。
2. Java 包名继续使用 `com.lx.mc.foundation`，不随 Maven 坐标迁移。
3. 父工程、核心模块和 Bukkit 模块必须使用完全一致的版本。
4. 业务插件只能依赖已经发布的明确版本，不使用版本范围。

## 2. 发布产物

1. 父工程发布 `lxmc-foundation` POM。
2. `foundation-core` 和 `foundation-bukkit` 各自发布主 JAR、源码 JAR、
   Javadoc JAR、POM 及其 GPG 签名。
3. Bukkit API 使用 `provided` 范围，不进入 Foundation 或业务插件产物。
4. Spigot 官方仅提供浮动 SNAPSHOT 时，必须锁定到官方仓库的具体时间戳构件，
   正式 POM 不得声明 `-SNAPSHOT` 依赖。
5. 业务插件通过 Shade 内置 Foundation，服务端不单独安装公共库。

## 3. 凭据边界

1. Central User Token、GPG 私钥和私钥密码不得进入 Git 仓库。
2. GitHub Actions 只从 `CENTRAL_USERNAME`、`CENTRAL_PASSWORD`、
   `GPG_PRIVATE_KEY` 和 `GPG_PASSPHRASE` Secrets 读取凭据。
3. 本地 Maven 配置和镜像只放在用户目录，不写入公共 POM。

## 4. 发布流程

1. 日常开发版本以 `-SNAPSHOT` 结尾。
2. 发布前将全部模块改成不带 `-SNAPSHOT` 的正式版本并执行
   `mvn -Prelease clean verify`。
3. 正式版本提交完成后创建同版本标签，例如 `v0.1.0`。
4. 标签触发 GitHub Actions 构建、签名并上传 Central Portal。
5. 部署进入 `VALIDATED` 后，手动运行验证工作流并使用全新 Maven 缓存从
   Central 验证仓库拉取正式坐标。
6. 远程消费验证通过后，在 Central Portal 人工点击 `Publish`。
7. 已发布版本不可覆盖；任何修复必须递增版本号。

## 5. 发布后验证

1. Central Portal 状态必须为 `PUBLISHED`。
2. 新建空 Maven 项目，只声明 Central 坐标即可下载 Foundation。
3. `OurTools` 等业务插件必须在干净 Maven 仓库中独立构建通过。
4. 发布结束后将下一开发版本推进到新的 `-SNAPSHOT` 版本。
