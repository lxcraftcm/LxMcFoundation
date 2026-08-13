package com.lx.mc.foundation.bukkit.i18n;

import com.lx.mc.foundation.i18n.MessageArguments;
import com.lx.mc.foundation.i18n.MessageDefinition;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.util.Objects;

/**
 * 将已着色旧式文本直接转换为 Bukkit 聊天组件。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：保留颜色与文本结构，不附加具体业务交互。</p>
 * <p>主要调用方：不需要悬浮或点击的业务插件国际化装配流程。</p>
 */
public final class BukkitLegacyMessageDecorator
        implements BukkitMessageDecorator {

    /**
     * 将单行旧式文本转换为组件数组。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：校验发送上下文后委托 TextComponent 保留旧式颜色。</p>
     * <p>主要调用方：BukkitLocalizedMessenger.send。</p>
     *
     * @param recipient 最终接收者
     * @param renderedLine 已本地化单行文本
     * @param definition 消息定义
     * @param arguments 具名参数
     * @param messageTime 发送时间
     * @return Bukkit 组件数组
     */
    @Override
    public BaseComponent[] decorate(
            CommandSender recipient,
            String renderedLine,
            MessageDefinition definition,
            MessageArguments arguments,
            Instant messageTime
    ) {
        // 1. 默认装饰器不使用业务上下文，但仍校验统一调用契约
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(messageTime, "messageTime");

        // 2. 保留已经生效的 Bukkit 旧式颜色并转换为组件数组
        return TextComponent.fromLegacyText(
                Objects.requireNonNull(renderedLine, "renderedLine")
        );
    }
}
