package com.lx.mc.foundation.bukkit.i18n;

import com.lx.mc.foundation.i18n.LocaleCode;
import com.lx.mc.foundation.i18n.MessageCatalogSnapshot;
import com.lx.mc.foundation.i18n.MessageDefinition;
import com.lx.mc.foundation.i18n.MessageService;
import com.lx.mc.foundation.i18n.MessageTemplate;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 从插件 JAR 内置 YAML 和插件目录外部覆盖构建完整语言快照。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>加载并完整校验全部内置语言。</li>
 *     <li>以 en_us 的占位符集合作为稳定消息契约。</li>
 *     <li>首次启动复制内置文件，但不覆盖管理员现有文件。</li>
 *     <li>外部覆盖逐键校验，单个无效项回退到内置模板。</li>
 * </ol>
 * <p>主要调用方：业务插件国际化启动和重载流程。</p>
 * <p>约束：load 执行文件 I/O，除插件启动外应在后台线程构建候选快照。</p>
 */
public final class BukkitYamlMessageCatalogLoader {

    /** 消息路径只允许安全小写分段。 */
    private static final Pattern SAFE_MESSAGE_PATH =
            Pattern.compile("[a-z0-9-]+(?:\\.[a-z0-9-]+)*");

    /** 用于读取 JAR 内置语言资源的插件类加载器。 */
    private final ClassLoader classLoader;
    /** 服务器管理员维护的外部语言文件目录。 */
    private final Path languageDirectory;
    /** 具体业务插件声明的稳定消息定义集合。 */
    private final List<MessageDefinition> definitions;
    /** 必须在 JAR 内完整提供的语言集合。 */
    private final Set<LocaleCode> builtInLocales;
    /** 记录无效外部覆盖和语言文件问题。 */
    private final Logger logger;

    /**
     * 创建一个由业务插件提供消息定义的 YAML 加载器。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：校验路径唯一性并复制定义与内置语言集合。</p>
     * <p>主要调用方：业务插件国际化装配流程。</p>
     *
     * @param classLoader 业务插件类加载器
     * @param languageDirectory 外部 lang 目录
     * @param definitions 完整消息定义集合
     * @param builtInLocales 必须内置的语言集合
     * @param logger 插件日志
     */
    public BukkitYamlMessageCatalogLoader(
            ClassLoader classLoader,
            Path languageDirectory,
            Iterable<? extends MessageDefinition> definitions,
            Iterable<LocaleCode> builtInLocales,
            Logger logger
    ) {
        // 1. 校验基础资源边界
        this.classLoader = Objects.requireNonNull(
                classLoader,
                "classLoader"
        );
        this.languageDirectory = Objects.requireNonNull(
                languageDirectory,
                "languageDirectory"
        );
        this.logger = Objects.requireNonNull(logger, "logger");

        // 2. 复制消息定义并拒绝重复或不安全路径
        Objects.requireNonNull(definitions, "definitions");
        List<MessageDefinition> copiedDefinitions =
                new ArrayList<MessageDefinition>();
        Set<String> paths = new LinkedHashSet<String>();
        for (MessageDefinition definition : definitions) {
            MessageDefinition checked = Objects.requireNonNull(
                    definition,
                    "definition"
            );
            String path = Objects.requireNonNull(
                    checked.getPath(),
                    "message path"
            );
            if (!SAFE_MESSAGE_PATH.matcher(path).matches()) {
                throw new IllegalArgumentException(
                        "Unsafe message path: " + path
                );
            }
            if (!paths.add(path)) {
                throw new IllegalArgumentException(
                        "Duplicate message path: " + path
                );
            }
            copiedDefinitions.add(checked);
        }
        if (copiedDefinitions.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one message definition is required"
            );
        }
        this.definitions = Collections.unmodifiableList(
                copiedDefinitions
        );

        // 3. 复制内置语言并确保 en_us 可以承担最终契约与回退
        Objects.requireNonNull(builtInLocales, "builtInLocales");
        Set<LocaleCode> copiedLocales = new LinkedHashSet<LocaleCode>();
        for (LocaleCode locale : builtInLocales) {
            copiedLocales.add(Objects.requireNonNull(locale, "locale"));
        }
        if (!copiedLocales.contains(MessageService.BUILT_IN_FALLBACK)) {
            throw new IllegalArgumentException(
                    "Built-in locales must contain en_us"
            );
        }
        this.builtInLocales = Collections.unmodifiableSet(copiedLocales);
    }

    /**
     * 从内置资源和外部覆盖构建一份完整候选快照。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>加载全部内置模板并建立 en_us 占位符契约。</li>
     *     <li>校验其他内置语言完整且契约相同。</li>
     *     <li>复制缺失的外部示例并加载全部外部 YAML。</li>
     *     <li>发布深度不可变的语言目录快照。</li>
     * </ol>
     * <p>主要调用方：插件启动与异步重载流程。</p>
     * <p>副作用：可能创建 lang 目录并复制首次启动语言文件。</p>
     *
     * @return 已完整校验的不可变语言快照
     * @throws IOException 资源、目录或 YAML 读取失败时抛出
     */
    public MessageCatalogSnapshot load() throws IOException {
        // 1. 先完整加载全部内置语言
        Map<LocaleCode, Map<String, MessageTemplate>> loaded =
                new LinkedHashMap<LocaleCode, Map<String, MessageTemplate>>();
        for (LocaleCode locale : builtInLocales) {
            loaded.put(locale, loadBuiltIn(locale));
        }

        // 2. en_us 作为每个消息路径的占位符契约
        Map<String, MessageTemplate> contracts = loaded.get(
                MessageService.BUILT_IN_FALLBACK
        );
        for (Map.Entry<LocaleCode, Map<String, MessageTemplate>> entry
                : loaded.entrySet()) {
            validateContracts(entry.getKey(), entry.getValue(), contracts);
        }

        // 3. 首次启动复制内置文件，不覆盖现有管理员修改
        Files.createDirectories(languageDirectory);
        for (LocaleCode locale : builtInLocales) {
            copyBuiltInIfMissing(locale);
        }

        // 4. 外部语言可覆盖内置项或作为只包含部分翻译的新语言
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                languageDirectory,
                "*.yml"
        )) {
            for (Path file : stream) {
                applyExternalFile(file, loaded, contracts);
            }
        }

        // 5. 只有完整候选构建成功后才创建可发布快照
        return new MessageCatalogSnapshot(loaded);
    }

    /**
     * 加载并解析一份 JAR 内置语言文件。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：打开 UTF-8 资源并要求全部定义路径存在。</p>
     * <p>主要调用方：load。</p>
     *
     * @param locale 待加载内置语言
     * @return 完整路径到模板映射
     * @throws IOException 资源缺失或 YAML 无效时抛出
     */
    private Map<String, MessageTemplate> loadBuiltIn(
            LocaleCode locale
    ) throws IOException {
        // 1. 内置资源路径只由已安全校验的语言代码组成
        String resourcePath = resourcePath(locale);
        YamlConfiguration configuration = loadResource(resourcePath);

        // 2. 内置语言必须覆盖全部业务消息定义
        Map<String, MessageTemplate> templates =
                new LinkedHashMap<String, MessageTemplate>();
        for (MessageDefinition definition : definitions) {
            MessageTemplate template = readTemplate(
                    configuration,
                    definition.getPath()
            );
            if (template == null) {
                throw new IOException(
                        "Missing built-in message "
                                + definition.getPath()
                                + " in "
                                + resourcePath
                );
            }
            templates.put(definition.getPath(), template);
        }
        return templates;
    }

    /**
     * 校验一种内置语言的占位符与 en_us 契约一致。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：逐路径比较两份模板提取的占位符集合。</p>
     * <p>主要调用方：load。</p>
     *
     * @param locale 当前被校验语言
     * @param templates 当前语言模板
     * @param contracts en_us 契约模板
     * @throws IOException 任一占位符集合不一致时抛出
     */
    private void validateContracts(
            LocaleCode locale,
            Map<String, MessageTemplate> templates,
            Map<String, MessageTemplate> contracts
    ) throws IOException {
        // 1. 内置语言不允许因翻译调整增删业务参数
        for (MessageDefinition definition : definitions) {
            Set<String> expected = contracts.get(
                    definition.getPath()
            ).getPlaceholders();
            Set<String> actual = templates.get(
                    definition.getPath()
            ).getPlaceholders();
            if (!expected.equals(actual)) {
                throw new IOException(
                        "Placeholder contract mismatch for "
                                + definition.getPath()
                                + " in "
                                + locale
                );
            }
        }
    }

    /**
     * 将内置语言文件复制到首次启动的外部目录。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：目标存在时保留管理员文件，否则复制原始 UTF-8 资源。</p>
     * <p>主要调用方：load。</p>
     *
     * @param locale 待复制内置语言
     * @throws IOException 资源缺失或复制失败时抛出
     */
    private void copyBuiltInIfMissing(LocaleCode locale) throws IOException {
        // 1. 外部语言文件存在时绝不覆盖
        Path target = languageDirectory.resolve(
                locale.getValue() + ".yml"
        );
        if (Files.exists(target)) {
            return;
        }

        // 2. 复制原始资源可以保留面向管理员的配置注释
        String resourcePath = resourcePath(locale);
        try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException(
                        "Missing built-in language resource: "
                                + resourcePath
                );
            }
            Files.copy(input, target);
        }
    }

    /**
     * 将一份外部 YAML 中的有效覆盖合并到候选目录。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：从安全文件名解析语言，以内置映射为基础逐键校验覆盖。</p>
     * <p>主要调用方：load。</p>
     *
     * @param file 外部 YAML 文件
     * @param loaded 当前候选语言目录
     * @param contracts en_us 占位符契约
     * @throws IOException YAML 整体无法解析时抛出
     */
    private void applyExternalFile(
            Path file,
            Map<LocaleCode, Map<String, MessageTemplate>> loaded,
            Map<String, MessageTemplate> contracts
    ) throws IOException {
        // 1. 语言代码只来自安全文件名，不使用 YAML 内容拼接路径
        String fileName = file.getFileName().toString();
        String rawLocale = fileName.substring(
                0,
                fileName.length() - ".yml".length()
        );
        LocaleCode locale;
        try {
            locale = LocaleCode.of(rawLocale);
        } catch (IllegalArgumentException exception) {
            logger.warning("Ignoring unsafe language file: " + file);
            return;
        }

        // 2. 内置语言以完整内置模板为基础，新语言从空映射开始
        YamlConfiguration configuration = loadFile(file);
        Map<String, MessageTemplate> merged =
                new LinkedHashMap<String, MessageTemplate>();
        Map<String, MessageTemplate> current = loaded.get(locale);
        if (current != null) {
            merged.putAll(current);
        }

        // 3. 单个覆盖类型或占位符不正确时忽略该项并保留基础模板
        for (MessageDefinition definition : definitions) {
            MessageTemplate override;
            try {
                override = readTemplate(
                        configuration,
                        definition.getPath()
                );
            } catch (IllegalArgumentException exception) {
                logger.warning(
                        "Ignoring invalid message override "
                                + definition.getPath()
                                + " in "
                                + file
                );
                continue;
            }
            if (override == null) {
                continue;
            }
            if (!override.getPlaceholders().equals(
                    contracts.get(definition.getPath()).getPlaceholders()
            )) {
                logger.warning(
                        "Ignoring placeholder-mismatched message override "
                                + definition.getPath()
                                + " in "
                                + file
                );
                continue;
            }
            merged.put(definition.getPath(), override);
        }
        if (!merged.isEmpty()) {
            loaded.put(locale, merged);
        }
    }

    /**
     * 从 YAML 指定路径读取单行或多行消息模板。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：字符串转换为单行集合，字符串列表保留原始行顺序。</p>
     * <p>主要调用方：loadBuiltIn 和 applyExternalFile。</p>
     *
     * @param configuration 已解析 YAML
     * @param path 稳定消息路径
     * @return 模板；路径缺失时返回 null
     * @throws IllegalArgumentException 路径值不是字符串或字符串列表时抛出
     */
    private MessageTemplate readTemplate(
            YamlConfiguration configuration,
            String path
    ) {
        // 1. 未声明路径由调用方决定是否必需
        if (!configuration.contains(path)) {
            return null;
        }

        // 2. 只接受明确字符串或字符串列表
        if (configuration.isString(path)) {
            return new MessageTemplate(
                    Collections.singletonList(
                            configuration.getString(path)
                    )
            );
        }
        if (configuration.isList(path)) {
            List<?> rawValues = configuration.getList(path);
            if (rawValues == null || rawValues.isEmpty()) {
                throw new IllegalArgumentException(
                        "Message list cannot be empty: " + path
                );
            }
            List<String> lines = new ArrayList<String>(rawValues.size());
            for (Object rawValue : rawValues) {
                if (!(rawValue instanceof String)) {
                    throw new IllegalArgumentException(
                            "Message list must contain only strings: " + path
                    );
                }
                lines.add((String) rawValue);
            }
            return new MessageTemplate(lines);
        }
        throw new IllegalArgumentException(
                "Message must be a string or string list: " + path
        );
    }

    /**
     * 从类路径以 UTF-8 加载内置 YAML。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：安全打开资源流并将 YAML 语法错误转为 IOException。</p>
     * <p>主要调用方：loadBuiltIn。</p>
     *
     * @param resourcePath 类路径资源位置
     * @return 已解析 YAML
     * @throws IOException 资源缺失或 YAML 无效时抛出
     */
    private YamlConfiguration loadResource(String resourcePath)
            throws IOException {
        // 1. 资源缺失属于发布包不完整，必须阻止候选快照发布
        try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException(
                        "Missing built-in language resource: "
                                + resourcePath
                );
            }
            try (Reader reader = new InputStreamReader(
                    input,
                    StandardCharsets.UTF_8
            )) {
                return loadYaml(reader, resourcePath);
            }
        }
    }

    /**
     * 从外部文件以 UTF-8 加载 YAML。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：打开 UTF-8 Reader 并委托统一 YAML 解析。</p>
     * <p>主要调用方：applyExternalFile。</p>
     *
     * @param file 外部 YAML 路径
     * @return 已解析 YAML
     * @throws IOException 文件读取或 YAML 解析失败时抛出
     */
    private YamlConfiguration loadFile(Path file) throws IOException {
        // 1. Files.newBufferedReader 确保外部语言统一使用 UTF-8
        try (Reader reader = Files.newBufferedReader(
                file,
                StandardCharsets.UTF_8
        )) {
            return loadYaml(reader, file.toString());
        }
    }

    /**
     * 将一个 Reader 解析为 Bukkit YAML 配置。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：创建独立 YamlConfiguration 并保留来源描述的错误上下文。</p>
     * <p>主要调用方：loadResource 和 loadFile。</p>
     *
     * @param reader UTF-8 文本读取器
     * @param description 用于错误诊断的来源说明
     * @return 已解析 YAML
     * @throws IOException YAML 语法无效时抛出
     */
    private YamlConfiguration loadYaml(
            Reader reader,
            String description
    ) throws IOException {
        // 1. 使用新实例避免多份语言共享可变 YAML 状态
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(reader);
            return configuration;
        } catch (InvalidConfigurationException exception) {
            throw new IOException(
                    "Invalid YAML configuration: " + description,
                    exception
            );
        }
    }

    /**
     * 构建指定内置语言的类路径资源位置。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：将已安全校验的语言代码放入固定 lang 目录。</p>
     * <p>主要调用方：loadBuiltIn 和 copyBuiltInIfMissing。</p>
     *
     * @param locale 已标准化语言代码
     * @return JAR 内部资源路径
     */
    private String resourcePath(LocaleCode locale) {
        // 1. LocaleCode 已拒绝目录分隔符，可安全构建固定路径
        return "lang/" + locale.getValue() + ".yml";
    }
}
