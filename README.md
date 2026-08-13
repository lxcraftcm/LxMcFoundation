# LxMcFoundation

LxMcFoundation 是 lxcraftcm 旗下 Minecraft Bukkit 插件的公共基础库。
它不是可独立安装的服务端插件，而是由 OurCity3、OurTools
等业务插件通过 Maven 依赖和 Shade 打包使用。

## 当前模块

- `foundation-core`：缓存、JSON 持久化、异步写后队列、数据生命周期、配置与国际化端口。
- `foundation-bukkit`：Bukkit 配置、语言解析、YAML 语言目录和消息发送适配。

## 本地构建

需要 Java 8 或更高版本的 JDK：

```bash
mvn clean install
```

成功后，两个模块会安装到本机 Maven 仓库，供具体插件按固定版本引用。

## Maven 依赖

稳定版本发布到 Maven Central 后，业务插件可以直接依赖 Bukkit 适配模块：

```xml
<dependency>
    <groupId>io.github.lxcraftcm</groupId>
    <artifactId>foundation-bukkit</artifactId>
    <version>0.1.0</version>
</dependency>
```

`foundation-bukkit` 不是 Bukkit 插件，业务插件仍需通过 Maven Shade
将其合入最终插件 JAR，服务端不单独安装 Foundation。

## 文档

- [公共基础库总文档](docs/development/0-公共基础库总文档.md)
- [公共 API 与版本规范](docs/development/1-公共API与版本规范.md)
- [Maven Central 发布规范](docs/development/2-MavenCentral发布规范.md)

## 边界

- 不包含小镇、投票、掉落物或其他业务模型。
- 不包含 `plugin.yml`、命令、监听器或插件入口。
- 不提供跨插件静态单例，每个插件管理自己的运行时实例。

## 许可证

本项目采用 [MIT License](LICENSE)，允许自由使用、修改、分发和商用，
再分发时需要保留原版权声明和许可证文本。
