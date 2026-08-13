package com.lx.mc.foundation.i18n;

/**
 * 定义将受信任消息模板颜色标记转换为平台文本格式的端口。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 14:20</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>接收尚未插入玩家可控参数的受信任模板。</li>
 *     <li>由平台适配器转换颜色和格式标记。</li>
 * </ol>
 *
 * <p>主要调用方：MessageService。</p>
 * <p>约束：调用方必须在插入业务参数前执行颜色转换，避免参数注入格式。</p>
 */
public interface MessageColorizer {

    /**
     * 转换受信任模板中的平台颜色标记。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     * <p>主要逻辑：将模板颜色语法转换为平台可识别文本。</p>
     * <p>主要调用方：MessageService。</p>
     *
     * @param trustedTemplate 尚未插入业务参数的受信任模板
     * @return 完成平台颜色转换后的文本
     */
    String colorize(String trustedTemplate);

}
