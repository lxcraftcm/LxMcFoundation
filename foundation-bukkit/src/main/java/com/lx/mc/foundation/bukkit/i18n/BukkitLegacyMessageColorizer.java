package com.lx.mc.foundation.bukkit.i18n;

import com.lx.mc.foundation.i18n.MessageColorizer;
import org.bukkit.ChatColor;

import java.util.Objects;

/**
 * 将受信任消息模板中的 {@code &} 颜色代码转换为 Bukkit 文本。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：委托 ChatColor 转换代码声明和语言模板中的颜色。</p>
 * <p>主要调用方：MessageService。</p>
 * <p>约束：动态业务参数必须在模板转换后才插入。</p>
 */
public final class BukkitLegacyMessageColorizer implements MessageColorizer {

    /**
     * 转换一段受信任的模板文本。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：使用 Bukkit 标准颜色转换边界处理 {@code &} 代码。</p>
     * <p>主要调用方：MessageService.render。</p>
     *
     * @param trustedTemplate 只由代码或语言文件提供的受信任模板
     * @return 已转换 Bukkit 颜色代码的文本
     */
    @Override
    public String colorize(String trustedTemplate) {
        // 1. 拒绝空模板并委托 Bukkit 完成标准颜色转换
        return ChatColor.translateAlternateColorCodes(
                '&',
                Objects.requireNonNull(trustedTemplate, "trustedTemplate")
        );
    }
}
