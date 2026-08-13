package com.lx.mc.foundation.i18n;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 保存消息渲染使用的不可变具名参数。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-08-13</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>校验占位符名称符合稳定规则。</li>
 *     <li>将业务值转换为文本快照。</li>
 *     <li>每次追加返回新对象，避免范围发送时共享可变参数。</li>
 * </ol>
 *
 * <p>主要调用方：命令、监听器和 MessageService。</p>
 * <p>约束：动态值在渲染时仍会移除颜色和控制字符。</p>
 */
public final class MessageArguments {

    /** 占位符名称必须以字母开头且只包含字母和数字。 */
    private static final Pattern VALID_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    /** 无参数消息共享的不可变空对象。 */
    private static final MessageArguments EMPTY =
            new MessageArguments(Collections.<String, String>emptyMap());

    /** 参数名到业务文本快照的不可修改映射。 */
    private final Map<String, String> values;

    /**
     * 创建并拷贝一组已校验参数。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：保存映射副本并禁止外部修改。</p>
     * <p>主要调用方：empty、of 和 and。</p>
     *
     * @param values 已完成名称与非空校验的参数
     */
    private MessageArguments(Map<String, String> values) {
        // 1. 复制当前顺序并对调用方发布不可修改视图
        this.values = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(values)
        );
    }

    /**
     * 获取不包含任何业务参数的共享对象。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：返回不可变空单例。</p>
     * <p>主要调用方：无动态占位符的消息。</p>
     *
     * @return 不可变空参数
     */
    public static MessageArguments empty() {
        // 1. 空对象无状态变化，可以安全共享
        return EMPTY;
    }

    /**
     * 创建只包含一个具名参数的对象。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：从空对象复用不可变追加逻辑。</p>
     * <p>主要调用方：单参数消息的交互层。</p>
     *
     * @param name 占位符名称
     * @param value 业务值
     * @return 包含单个参数的新对象
     */
    public static MessageArguments of(String name, Object value) {
        // 1. 单参数创建与后续追加使用同一校验边界
        return EMPTY.and(name, value);
    }

    /**
     * 返回追加或覆盖指定具名参数后的新对象。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>校验名称和值。</li>
     *     <li>复制当前映射并覆盖同名参数。</li>
     *     <li>返回新的不可变参数对象。</li>
     * </ol>
     * <p>主要调用方：命令和事件消息参数组装流程。</p>
     *
     * @param name 符合命名规则的占位符名称
     * @param value 不能为空的业务值
     * @return 包含新参数的不可变对象
     * @throws IllegalArgumentException 参数名不合法时抛出
     */
    public MessageArguments and(String name, Object value) {
        // 1. 在创建新快照前拒绝无法对应模板的参数名
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid message argument name: " + name
            );
        }

        // 2. 复制当前参数并将值固化为当前文本快照
        Map<String, String> copied =
                new LinkedHashMap<String, String>(values);
        copied.put(name, String.valueOf(value));

        // 3. 返回新对象，不修改当前实例
        return new MessageArguments(copied);
    }

    /**
     * 查询指定占位符的当前文本值。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：从不可变映射读取已固化文本。</p>
     * <p>主要调用方：MessageService。</p>
     *
     * @param name 占位符名称
     * @return 已提供文本，不存在时返回 null
     */
    public String get(String name) {
        // 1. 参数映射不可变，可直接读取
        return values.get(name);
    }

    /**
     * 获取当前已提供的全部占位符名称。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：返回不可变映射的键视图。</p>
     * <p>主要调用方：MessageService 的参数契约检查。</p>
     *
     * @return 不可修改的参数名集合
     */
    public Set<String> names() {
        // 1. 底层映射不可修改，键视图同样不可修改
        return values.keySet();
    }
}
