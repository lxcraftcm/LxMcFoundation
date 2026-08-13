package com.lx.mc.foundation.bukkit.i18n;

import com.lx.mc.foundation.i18n.LocaleCode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * 根据 Bukkit 消息接收者解析当前目标语言。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：玩家使用客户端上报语言，控制台和其他发送者使用服务器默认语言。</p>
 * <p>主要调用方：BukkitLocalizedMessenger。</p>
 */
public final class BukkitLocaleResolver {

    /** 非玩家接收者和非法客户端语言的回退值。 */
    private final LocaleCode defaultLocale;

    /**
     * 创建使用指定服务器默认语言的解析器。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：校验并保存不可变默认语言。</p>
     * <p>主要调用方：插件国际化装配流程。</p>
     *
     * @param defaultLocale 服务器默认语言
     */
    public BukkitLocaleResolver(LocaleCode defaultLocale) {
        // 1. 非玩家接收者始终必须有可用语言
        this.defaultLocale = Objects.requireNonNull(
                defaultLocale,
                "defaultLocale"
        );
    }

    /**
     * 解析一个 Bukkit 接收者的当前语言。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>玩家每次发送时读取当前客户端语言。</li>
     *     <li>非法语言代码安全回退。</li>
     *     <li>非玩家接收者直接使用默认语言。</li>
     * </ol>
     * <p>主要调用方：BukkitLocalizedMessenger.send 和 render。</p>
     *
     * @param recipient Bukkit 消息接收者
     * @return 标准化后的目标语言
     */
    public LocaleCode resolve(CommandSender recipient) {
        // 1. 玩家语言在发送当下读取，不保存过期快照
        Objects.requireNonNull(recipient, "recipient");
        if (recipient instanceof Player) {
            try {
                return LocaleCode.of(((Player) recipient).getLocale());
            } catch (IllegalArgumentException exception) {
                return defaultLocale;
            }
        }

        // 2. 控制台、命令方块等没有客户端语言
        return defaultLocale;
    }
}
