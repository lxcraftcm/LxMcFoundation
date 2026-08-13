package com.lx.mc.foundation.i18n;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 表示经过标准化和安全校验的 Minecraft 客户端语言代码。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 14:20</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>将语言代码转换为小写下划线格式。</li>
 *     <li>拒绝可能用于目录穿越或非法文件名的字符。</li>
 *     <li>以不可变值对象形式参与语言目录查询。</li>
 * </ol>
 *
 * <p>主要调用方：国际化配置、Bukkit 语言解析器和消息目录。</p>
 * <p>约束：语言代码只能由 of 方法创建，不能直接作为未经校验的文件路径。</p>
 */
public final class LocaleCode {

    /**
     * 允许语言代码由一到多个小写字母或数字分段组成，并使用下划线分隔。
     */
    private static final Pattern SAFE_LOCALE_CODE =
            Pattern.compile("[a-z0-9]{2,8}(?:_[a-z0-9]{2,8})*");

    /**
     * 标准化后的稳定语言代码。
     */
    private final String value;

    /**
     * 创建一个已经完成格式校验的语言代码。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     * <p>主要逻辑：保存由工厂方法完成标准化和校验的语言代码。</p>
     * <p>主要调用方：LocaleCode.of 方法。</p>
     *
     * @param value 标准化后的安全语言代码
     */
    private LocaleCode(String value) {
        // 1. 保存已经通过安全格式校验的语言代码
        this.value = value;
    }

    /**
     * 标准化并创建语言代码值对象。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>去除首尾空白并使用根区域规则转换为小写。</li>
     *     <li>将连字符转换为 Minecraft 常用的下划线格式。</li>
     *     <li>校验安全格式后创建不可变值对象。</li>
     * </ol>
     *
     * <p>主要调用方：配置解析、玩家语言解析和语言文件扫描。</p>
     *
     * @param rawValue 原始语言代码，不能为空
     * @return 标准化后的语言代码
     * @throws IllegalArgumentException 原始值为空或包含非法字符时抛出
     */
    public static LocaleCode of(String rawValue) {
        // 1. 校验并标准化大小写、空白和分隔符
        Objects.requireNonNull(rawValue, "rawValue");
        String normalized = rawValue
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');

        // 2. 拒绝空值、目录分隔符和其他不安全语言代码
        if (!SAFE_LOCALE_CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Invalid locale code: " + rawValue
            );
        }

        // 3. 返回只包含安全标准化值的语言代码对象
        return new LocaleCode(normalized);
    }

    /**
     * 获取标准化后的语言代码文本。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     * <p>主要逻辑：返回构造阶段保存的不可变语言代码。</p>
     * <p>主要调用方：语言目录、日志和外部语言文件适配器。</p>
     *
     * @return 标准化语言代码
     */
    public String getValue() {
        // 1. 返回不可变的标准化语言代码
        return value;
    }

    /**
     * 判断另一个对象是否表示相同的标准化语言代码。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     * <p>主要逻辑：比较对象类型和标准化语言代码值。</p>
     * <p>主要调用方：Map、Set 和测试断言。</p>
     *
     * @param other 待比较对象
     * @return true 表示两个语言代码值相同
     */
    @Override
    public boolean equals(Object other) {
        // 1. 相同对象引用直接判定相等
        if (this == other) {
            return true;
        }

        // 2. 类型不同或为空时判定不相等
        if (!(other instanceof LocaleCode)) {
            return false;
        }

        // 3. 比较两个对象的标准化语言代码
        LocaleCode that = (LocaleCode) other;
        return value.equals(that.value);
    }

    /**
     * 计算语言代码值对象的稳定哈希值。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     * <p>主要逻辑：使用标准化语言代码计算哈希值。</p>
     * <p>主要调用方：Map 和 Set。</p>
     *
     * @return 标准化语言代码的哈希值
     */
    @Override
    public int hashCode() {
        // 1. 返回与 equals 使用字段一致的哈希值
        return value.hashCode();
    }

    /**
     * 返回标准化语言代码的文本表示。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 14:20</p>
     * <p>主要逻辑：返回适合配置和日志使用的语言代码。</p>
     * <p>主要调用方：日志、测试和配置诊断。</p>
     *
     * @return 标准化语言代码
     */
    @Override
    public String toString() {
        // 1. 返回稳定语言代码文本
        return value;
    }
}
