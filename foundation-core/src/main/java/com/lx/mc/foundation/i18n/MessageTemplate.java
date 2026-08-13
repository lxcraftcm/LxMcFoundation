package com.lx.mc.foundation.i18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表示经过基础校验的一行或多行不可变消息模板。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 14:20</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>复制并保存消息模板行。</li>
 *     <li>从全部模板行提取具名占位符集合。</li>
 *     <li>向目录和渲染服务提供不可修改视图。</li>
 * </ol>
 *
 * <p>主要调用方：YAML 消息目录加载器和 MessageService。</p>
 * <p>约束：模板至少包含一行，所有占位符使用花括号具名格式。</p>
 */
public final class MessageTemplate {

    /**
     * 匹配由字母开头且仅包含字母和数字的具名占位符。
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    /**
     * 保存当前消息模板的不可变文本行。
     */
    private final List<String> lines;

    /**
     * 保存从全部模板行提取的不可变占位符集合。
     */
    private final Set<String> placeholders;

    /**
     * 创建并分析一行或多行消息模板。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>校验模板行集合至少包含一项。</li>
     *     <li>复制每一行并提取具名占位符。</li>
     *     <li>保存不可修改的行和占位符集合。</li>
     * </ol>
     *
     * <p>主要调用方：YAML 消息目录加载器和测试。</p>
     *
     * @param lines 消息模板行，不能为空且至少包含一项
     * @throws IllegalArgumentException 模板行集合为空时抛出
     */
    public MessageTemplate(List<String> lines) {
        // 1. 校验模板行集合存在且至少包含一行
        Objects.requireNonNull(lines, "lines");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(
                    "Message template must contain at least one line"
            );
        }

        // 2. 复制模板行并从每一行提取具名占位符
        List<String> copiedLines = new ArrayList<String>(lines.size());
        Set<String> extractedPlaceholders = new LinkedHashSet<String>();
        for (String line : lines) {
            String checkedLine = Objects.requireNonNull(line, "template line");
            copiedLines.add(checkedLine);

            Matcher matcher = PLACEHOLDER_PATTERN.matcher(checkedLine);
            while (matcher.find()) {
                extractedPlaceholders.add(matcher.group(1));
            }
        }

        // 3. 保存不可变模板行和占位符契约
        this.lines = Collections.unmodifiableList(copiedLines);
        this.placeholders = Collections.unmodifiableSet(
                extractedPlaceholders
        );
    }

    /**
     * 获取当前消息模板的全部文本行。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     * <p>主要逻辑：返回构造阶段保存的不可变模板行。</p>
     * <p>主要调用方：MessageService。</p>
     *
     * @return 不可修改的消息模板行
     */
    public List<String> getLines() {
        // 1. 返回不可变消息模板行
        return lines;
    }

    /**
     * 获取当前消息模板使用的全部具名占位符。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     * <p>主要逻辑：返回从全部模板行提取的不可变占位符集合。</p>
     * <p>主要调用方：目录完整性校验和 MessageService。</p>
     *
     * @return 不可修改的具名占位符集合
     */
    public Set<String> getPlaceholders() {
        // 1. 返回不可变占位符契约
        return placeholders;
    }
}
