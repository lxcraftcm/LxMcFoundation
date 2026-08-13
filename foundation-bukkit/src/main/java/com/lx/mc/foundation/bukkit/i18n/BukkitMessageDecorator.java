package com.lx.mc.foundation.bukkit.i18n;

import com.lx.mc.foundation.i18n.MessageArguments;
import com.lx.mc.foundation.i18n.MessageDefinition;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.command.CommandSender;

import java.time.Instant;

/**
 * 定义业务插件对已本地化单行消息的可选组件装饰端口。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：让具体插件在统一发送边界增加点击、悬浮或时间信息。</p>
 * <p>主要调用方：BukkitLocalizedMessenger。</p>
 * <p>约束：装饰器不得执行文件或网络 I/O。</p>
 */
public interface BukkitMessageDecorator {

    /**
     * 将已渲染文本转换为当前接收者的最终 Bukkit 组件。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：保留旧式颜色文本，并按业务上下文可选拆分交互组件。</p>
     * <p>主要调用方：BukkitLocalizedMessenger.send。</p>
     *
     * @param recipient 最终接收者
     * @param renderedLine 已本地化与着色的单行文本
     * @param definition 稳定消息定义
     * @param arguments 当前消息的具名参数
     * @param messageTime 整条消息共享的发送时间
     * @return 可直接发送的 Bukkit 组件数组
     */
    BaseComponent[] decorate(
            CommandSender recipient,
            String renderedLine,
            MessageDefinition definition,
            MessageArguments arguments,
            Instant messageTime
    );
}
