package com.lx.mc.foundation.i18n;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 使用单个 volatile 引用原子发布完整语言目录快照。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：发送路径只读取当前快照，重载成功后一次性替换完整目录。</p>
 * <p>主要调用方：MessageService 和业务插件国际化重载流程。</p>
 * <p>约束：候选快照必须在 replace 前完成全部文件与占位符校验。</p>
 */
public final class ReloadableMessageCatalog implements MessageCatalog {

    /** 当前对全部发送线程可见的完整不可变快照。 */
    private volatile MessageCatalog current;

    /**
     * 使用首份已校验快照创建可重载目录。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：校验并发布初始快照引用。</p>
     * <p>主要调用方：插件国际化启动流程。</p>
     *
     * @param initialCatalog 启动阶段已完整校验的目录
     */
    public ReloadableMessageCatalog(MessageCatalog initialCatalog) {
        // 1. 发送路径不允许观察到空目录
        this.current = Objects.requireNonNull(
                initialCatalog,
                "initialCatalog"
        );
    }

    /**
     * 原子替换当前完整语言目录。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：在候选快照全部就绪后一次替换 volatile 引用。</p>
     * <p>主要调用方：管理员国际化重载流程。</p>
     * <p>副作用：后续消息查询立即使用新快照。</p>
     *
     * @param replacement 已完整校验的替换目录
     */
    public void replace(MessageCatalog replacement) {
        // 1. 一次性发布完整对象，不逐语言修改当前快照
        current = Objects.requireNonNull(replacement, "replacement");
    }

    /**
     * 从当前快照查询指定消息模板。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：仅读取一次当前快照引用并委托查询。</p>
     * <p>主要调用方：MessageService。</p>
     *
     * @param locale 目标语言
     * @param definition 消息定义
     * @return 当前快照的查询结果
     */
    @Override
    public Optional<MessageTemplate> find(
            LocaleCode locale,
            MessageDefinition definition
    ) {
        // 1. volatile 读取确保单次查询只使用一份完整快照
        MessageCatalog snapshot = current;
        return snapshot.find(locale, definition);
    }

    /**
     * 获取当前快照已加载的语言集合。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：对同一次快照读取委托语言集合查询。</p>
     * <p>主要调用方：语言别名校验和启动诊断。</p>
     *
     * @return 当前不可变语言集合
     */
    @Override
    public Set<LocaleCode> getAvailableLocales() {
        // 1. 快照提供不可变集合，可以直接委托
        MessageCatalog snapshot = current;
        return snapshot.getAvailableLocales();
    }
}
