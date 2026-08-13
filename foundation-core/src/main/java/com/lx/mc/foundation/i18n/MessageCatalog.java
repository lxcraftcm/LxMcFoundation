package com.lx.mc.foundation.i18n;

import java.util.Optional;
import java.util.Set;

/**
 * 定义从当前内存快照查询本地化消息模板的端口。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：按标准化语言和稳定消息路径查询不可变模板。</p>
 * <p>主要调用方：MessageService 和国际化启动校验。</p>
 * <p>约束：查询方法不得执行文件 I/O。</p>
 */
public interface MessageCatalog {

    /**
     * 查询指定语言与消息定义的模板。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：从当前快照的两层映射中读取模板。</p>
     * <p>主要调用方：MessageService。</p>
     *
     * @param locale 目标语言
     * @param definition 业务插件声明的消息定义
     * @return 找到时包含模板，否则返回空
     */
    Optional<MessageTemplate> find(
            LocaleCode locale,
            MessageDefinition definition
    );

    /**
     * 获取当前快照已加载的全部语言。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：返回当前不可变快照的语言集合。</p>
     * <p>主要调用方：启动诊断和语言别名校验。</p>
     *
     * @return 不可修改的语言集合
     */
    Set<LocaleCode> getAvailableLocales();
}
