package com.lx.mc.foundation.i18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 负责语言回退、占位符契约、动态值清理和最终文本渲染。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>按精确语言、别名、默认语言和英语保底查找模板。</li>
 *     <li>校验调用方提供了模板需要的全部业务参数。</li>
 *     <li>先处理受信任模板颜色，再按字面量插入已清理的动态文本。</li>
 * </ol>
 * <p>主要调用方：Bukkit 本地化消息发送器和菜单展示适配器。</p>
 * <p>约束：渲染路径只读内存快照，不执行文件或网络 I/O。</p>
 */
public final class MessageService {

    /** 公共库约定的内置最终保底语言。 */
    public static final LocaleCode BUILT_IN_FALLBACK =
            LocaleCode.of("en_us");

    /** 运行期只读的语言目录。 */
    private final MessageCatalog catalog;
    /** 非玩家或无匹配语言使用的默认语言。 */
    private final LocaleCode defaultLocale;
    /** 语言代码到已加载语言的别名映射。 */
    private final Map<LocaleCode, LocaleCode> aliases;
    /** 将模板中的受信任颜色标记转为平台文本。 */
    private final MessageColorizer colorizer;
    /** 记录缺失键与契约问题的插件日志。 */
    private final Logger logger;
    /** 避免同一缺失消息路径在高频调用中重复刷屏。 */
    private final Set<String> warnedMissingPaths;

    /**
     * 创建一个只依赖内存目录的消息渲染服务。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：校验依赖、复制别名快照并创建缺失日志去重集合。</p>
     * <p>主要调用方：业务插件国际化装配流程。</p>
     *
     * @param catalog 已加载的内存语言目录
     * @param defaultLocale 服务器默认语言
     * @param aliases 已校验的语言别名
     * @param colorizer 目标平台颜色转换器
     * @param logger 插件日志
     */
    public MessageService(
            MessageCatalog catalog,
            LocaleCode defaultLocale,
            Map<LocaleCode, LocaleCode> aliases,
            MessageColorizer colorizer,
            Logger logger
    ) {
        // 1. 校验不可缺失的目录、默认语言、颜色器和日志
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.defaultLocale = Objects.requireNonNull(
                defaultLocale,
                "defaultLocale"
        );
        this.colorizer = Objects.requireNonNull(colorizer, "colorizer");
        this.logger = Objects.requireNonNull(logger, "logger");

        // 2. 复制语言别名，避免配置重载在当前服务中局部生效
        Objects.requireNonNull(aliases, "aliases");
        this.aliases = Collections.unmodifiableMap(
                new LinkedHashMap<LocaleCode, LocaleCode>(aliases)
        );

        // 3. 缺失日志在多接收者范围发送时仍保持去重
        this.warnedMissingPaths = Collections.newSetFromMap(
                new ConcurrentHashMap<String, Boolean>()
        );
    }

    /**
     * 按指定语言渲染一条或多条本地化消息。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>按固定回退链查找模板。</li>
     *     <li>检查全部必需业务占位符。</li>
     *     <li>转换受信任颜色后按字面量替换已清理参数。</li>
     * </ol>
     * <p>主要调用方：BukkitLocalizedMessenger。</p>
     *
     * @param locale 目标语言
     * @param definition 业务消息定义
     * @param arguments 不可变具名参数
     * @return 不可修改的最终消息行
     * @throws IllegalArgumentException 调用方缺少模板必需参数时抛出
     */
    public List<String> render(
            LocaleCode locale,
            MessageDefinition definition,
            MessageArguments arguments
    ) {
        // 1. 校验调用边界并按回退链获取模板
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(arguments, "arguments");
        Optional<MessageTemplate> located = findWithFallback(
                locale,
                definition
        );
        if (!located.isPresent()) {
            warnMissing(definition.getPath());
            return Collections.singletonList(
                    "[missing:" + definition.getPath() + "]"
            );
        }

        // 2. prefix 由消息定义提供，其他占位符必须由业务调用方提供
        MessageTemplate template = located.get();
        Set<String> required = new HashSet<String>(
                template.getPlaceholders()
        );
        required.remove("prefix");
        if (!arguments.names().containsAll(required)) {
            required.removeAll(arguments.names());
            throw new IllegalArgumentException(
                    "Missing message arguments for "
                            + definition.getPath()
                            + ": "
                            + required
            );
        }

        // 3. 先转换受信任模板，再字面替换无法注入样式的动态值
        String renderedPrefix = colorizer.colorize(
                Objects.requireNonNull(
                        definition.getPrefixTemplate(),
                        "prefixTemplate"
                )
        );
        List<String> renderedLines = new ArrayList<String>(
                template.getLines().size()
        );
        for (String sourceLine : template.getLines()) {
            String rendered = colorizer.colorize(sourceLine)
                    .replace("{prefix}", renderedPrefix);
            for (String name : required) {
                rendered = rendered.replace(
                        "{" + name + "}",
                        sanitize(arguments.get(name))
                );
            }
            renderedLines.add(rendered);
        }
        return Collections.unmodifiableList(renderedLines);
    }

    /**
     * 按固定语言回退顺序查找消息模板。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：去重尝试精确语言、别名、默认语言和 en_us。</p>
     * <p>主要调用方：render。</p>
     *
     * @param requested 请求语言
     * @param definition 消息定义
     * @return 首个匹配模板
     */
    private Optional<MessageTemplate> findWithFallback(
            LocaleCode requested,
            MessageDefinition definition
    ) {
        // 1. 以插入顺序建立去重候选链
        List<LocaleCode> candidates = new ArrayList<LocaleCode>();
        candidates.add(requested);
        LocaleCode alias = aliases.get(requested);
        if (alias != null) {
            candidates.add(alias);
        }
        candidates.add(defaultLocale);
        candidates.add(BUILT_IN_FALLBACK);

        // 2. 返回固定顺序中第一个存在的模板
        Set<LocaleCode> visited = new HashSet<LocaleCode>();
        for (LocaleCode candidate : candidates) {
            if (visited.add(candidate)) {
                Optional<MessageTemplate> template = catalog.find(
                        candidate,
                        definition
                );
                if (template.isPresent()) {
                    return template;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 清理不可信任的动态参数文本。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：移除 Minecraft 颜色引导符和换行控制字符。</p>
     * <p>主要调用方：render 占位符替换流程。</p>
     *
     * @param value 业务文本快照
     * @return 可安全嵌入已着色模板的单行文本
     */
    private String sanitize(String value) {
        // 1. 参数已由 MessageArguments 保证存在，这里只处理展示控制字符
        return Objects.requireNonNull(value, "argument value")
                .replace('\u00A7', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ');
    }

    /**
     * 对同一缺失消息路径只记录一次警告。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：使用并发集合的首次插入结果控制日志。</p>
     * <p>主要调用方：render 缺失回退分支。</p>
     *
     * @param path 缺失的稳定消息路径
     */
    private void warnMissing(String path) {
        // 1. 范围发送中只有第一个接收者触发日志
        if (warnedMissingPaths.add(path)) {
            logger.warning("Missing localized message: " + path);
        }
    }
}
