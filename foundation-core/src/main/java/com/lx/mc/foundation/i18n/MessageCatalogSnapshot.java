package com.lx.mc.foundation.i18n;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 保存一次完整加载得到的不可变语言目录快照。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：深度复制语言到消息路径的两层映射，并提供无 I/O 查询。</p>
 * <p>主要调用方：Bukkit YAML 加载器和 ReloadableMessageCatalog。</p>
 */
public final class MessageCatalogSnapshot implements MessageCatalog {

    /** 语言到消息路径与模板的不可修改两层映射。 */
    private final Map<LocaleCode, Map<String, MessageTemplate>> templates;

    /** 当前快照已加载的不可修改语言集合。 */
    private final Set<LocaleCode> availableLocales;

    /**
     * 从候选两层映射创建不可变快照。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>校验语言、路径和模板非空。</li>
     *     <li>复制每种语言的模板映射。</li>
     *     <li>发布不可修改的两层快照。</li>
     * </ol>
     * <p>主要调用方：语言目录加载器。</p>
     *
     * @param templates 完整候选语言目录
     */
    public MessageCatalogSnapshot(
            Map<LocaleCode, Map<String, MessageTemplate>> templates
    ) {
        // 1. 深度复制每种语言，阻断加载器后续修改
        Objects.requireNonNull(templates, "templates");
        Map<LocaleCode, Map<String, MessageTemplate>> copied =
                new LinkedHashMap<LocaleCode, Map<String, MessageTemplate>>();
        for (Map.Entry<LocaleCode, Map<String, MessageTemplate>> entry
                : templates.entrySet()) {
            LocaleCode locale = Objects.requireNonNull(
                    entry.getKey(),
                    "locale"
            );
            Map<String, MessageTemplate> localeTemplates =
                    Objects.requireNonNull(entry.getValue(), "locale templates");
            Map<String, MessageTemplate> copiedTemplates =
                    new LinkedHashMap<String, MessageTemplate>();
            for (Map.Entry<String, MessageTemplate> templateEntry
                    : localeTemplates.entrySet()) {
                copiedTemplates.put(
                        Objects.requireNonNull(templateEntry.getKey(), "path"),
                        Objects.requireNonNull(templateEntry.getValue(), "template")
                );
            }
            copied.put(
                    locale,
                    Collections.unmodifiableMap(copiedTemplates)
            );
        }

        // 2. 两层映射与语言集合作为同一批快照发布
        this.templates = Collections.unmodifiableMap(copied);
        this.availableLocales = Collections.unmodifiableSet(
                new LinkedHashSet<LocaleCode>(copied.keySet())
        );
    }

    /**
     * 查询指定语言与消息定义的模板。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：先定位语言映射，再按消息路径查询。</p>
     * <p>主要调用方：MessageService。</p>
     *
     * @param locale 目标语言
     * @param definition 消息定义
     * @return 找到时包含模板
     */
    @Override
    public Optional<MessageTemplate> find(
            LocaleCode locale,
            MessageDefinition definition
    ) {
        // 1. 拒绝空查询并从快照中定位目标语言
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(definition, "definition");
        Map<String, MessageTemplate> localeTemplates = templates.get(locale);
        if (localeTemplates == null) {
            return Optional.empty();
        }

        // 2. 以业务枚举声明的稳定路径查询模板
        return Optional.ofNullable(
                localeTemplates.get(definition.getPath())
        );
    }

    /**
     * 获取当前快照已加载的全部语言。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：返回构造时保存的不可修改语言集合。</p>
     * <p>主要调用方：启动诊断和语言别名校验。</p>
     *
     * @return 不可修改的语言集合
     */
    @Override
    public Set<LocaleCode> getAvailableLocales() {
        // 1. 语言集合不可变，可以直接返回
        return availableLocales;
    }
}
