package com.lx.mc.foundation.bukkit.i18n;

import com.lx.mc.foundation.i18n.LocaleCode;
import com.lx.mc.foundation.i18n.MessageArguments;
import com.lx.mc.foundation.i18n.MessageDefinition;
import com.lx.mc.foundation.i18n.MessageService;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 向 Bukkit 接收者发送逐接收者本地化的聊天消息。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>在发送当下解析接收者语言。</li>
 *     <li>调用平台无关 MessageService 渲染一行或多行文本。</li>
 *     <li>使用可替换装饰器生成组件并统一发送。</li>
 * </ol>
 * <p>主要调用方：Bukkit 命令、监听器和范围广播适配器。</p>
 * <p>约束：调用方应在 Bukkit 允许发送组件的主线程使用。</p>
 */
public final class BukkitLocalizedMessenger {

    /** 按接收者类型解析当前语言。 */
    private final BukkitLocaleResolver localeResolver;
    /** 负责语言回退、参数契约和文本渲染。 */
    private final MessageService messageService;
    /** 将单行文本转换为可交互 Bukkit 组件的扩展点。 */
    private final BukkitMessageDecorator messageDecorator;

    /**
     * 创建使用指定语言、渲染和组件策略的发送器。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：校验并保存三个只读协作组件。</p>
     * <p>主要调用方：业务插件国际化装配流程。</p>
     *
     * @param localeResolver Bukkit 接收者语言解析器
     * @param messageService 平台无关消息渲染服务
     * @param messageDecorator 最终 Bukkit 组件装饰器
     */
    public BukkitLocalizedMessenger(
            BukkitLocaleResolver localeResolver,
            MessageService messageService,
            BukkitMessageDecorator messageDecorator
    ) {
        // 1. 发送路径必须始终具备语言、渲染和组件策略
        this.localeResolver = Objects.requireNonNull(
                localeResolver,
                "localeResolver"
        );
        this.messageService = Objects.requireNonNull(
                messageService,
                "messageService"
        );
        this.messageDecorator = Objects.requireNonNull(
                messageDecorator,
                "messageDecorator"
        );
    }

    /**
     * 按接收者语言渲染并发送一条或多条消息。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>解析语言并生成整条消息共享的时间快照。</li>
     *     <li>渲染全部消息行。</li>
     *     <li>逐行装饰并发送 Bukkit 组件。</li>
     * </ol>
     * <p>主要调用方：命令和事件交互层。</p>
     * <p>副作用：向指定 Bukkit 接收者发送聊天组件。</p>
     *
     * @param recipient 最终消息接收者
     * @param definition 稳定业务消息定义
     * @param arguments 不可变具名参数
     */
    public void send(
            CommandSender recipient,
            MessageDefinition definition,
            MessageArguments arguments
    ) {
        // 1. 每次发送读取最新客户端语言并固化消息时间
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(arguments, "arguments");
        LocaleCode locale = localeResolver.resolve(recipient);
        Instant messageTime = Instant.now();

        // 2. 渲染路径只读语言内存快照
        List<String> renderedLines = messageService.render(
                locale,
                definition,
                arguments
        );

        // 3. 同一条多行消息的全部组件共享接收者与时间上下文
        for (String renderedLine : renderedLines) {
            BaseComponent[] components = messageDecorator.decorate(
                    recipient,
                    renderedLine,
                    definition,
                    arguments,
                    messageTime
            );
            recipient.spigot().sendMessage(components);
        }
    }

    /**
     * 按接收者语言渲染消息但不执行发送。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：解析语言后委托 MessageService 返回最终文本行。</p>
     * <p>主要调用方：Title、ActionBar、菜单和测试适配器。</p>
     *
     * @param recipient 用于解析语言的 Bukkit 接收者
     * @param definition 消息定义
     * @param arguments 具名参数
     * @return 不可修改的最终消息行
     */
    public List<String> render(
            CommandSender recipient,
            MessageDefinition definition,
            MessageArguments arguments
    ) {
        // 1. 本方法不执行 Bukkit 发送，只复用相同语言解析和文本渲染
        return messageService.render(
                localeResolver.resolve(
                        Objects.requireNonNull(recipient, "recipient")
                ),
                Objects.requireNonNull(definition, "definition"),
                Objects.requireNonNull(arguments, "arguments")
        );
    }
}
