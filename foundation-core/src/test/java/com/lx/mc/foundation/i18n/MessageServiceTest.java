package com.lx.mc.foundation.i18n;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * 验证公共国际化服务的回退、安全替换和占位符契约。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：使用内存目录与可观察颜色器测试不依赖 Bukkit 的渲染链路。</p>
 * <p>主要调用方：Maven Surefire。</p>
 */
public final class MessageServiceTest {

    /**
     * 验证语言别名回退与动态文本安全边界。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：构建英文目录，将 en_gb 别名到 en_us，再确认颜色与控制字符不能注入。</p>
     * <p>主要调用方：JUnit。</p>
     */
    @Test
    public void shouldFallbackAndSanitizeArguments() {
        // 1. 只提供 en_us，让 en_gb 必须通过别名查找模板
        LocaleCode english = LocaleCode.of("en_us");
        Map<String, MessageTemplate> englishTemplates =
                new LinkedHashMap<String, MessageTemplate>();
        englishTemplates.put(
                TestMessage.HELLO.getPath(),
                new MessageTemplate(
                        Collections.singletonList(
                                "{prefix}&fHello {name}"
                        )
                )
        );
        Map<LocaleCode, Map<String, MessageTemplate>> catalogs =
                new LinkedHashMap<LocaleCode, Map<String, MessageTemplate>>();
        catalogs.put(english, englishTemplates);
        Map<LocaleCode, LocaleCode> aliases =
                new LinkedHashMap<LocaleCode, LocaleCode>();
        aliases.put(LocaleCode.of("en_gb"), english);

        // 2. 颜色器用可观察标记证明受信任模板与动态参数处理顺序
        MessageService service = new MessageService(
                new MessageCatalogSnapshot(catalogs),
                english,
                aliases,
                new MessageColorizer() {
                    /**
                     * 将测试颜色语法转换为容易断言的标记。
                     *
                     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
                     * <p>主要逻辑：字面替换两种测试颜色代码。</p>
                     * <p>主要调用方：MessageService。</p>
                     *
                     * @param trustedTemplate 受信任测试模板
                     * @return 已转换标记的文本
                     */
                    @Override
                    public String colorize(String trustedTemplate) {
                        // 1. 只转换模板预置代码，不解析后插入参数
                        return trustedTemplate
                                .replace("&a", "<green>")
                                .replace("&f", "<white>");
                    }
                },
                Logger.getLogger("MessageServiceTest")
        );

        // 3. 动态颜色引导符和换行被清理，但受信任前缀与模板颜色正常生效
        List<String> rendered = service.render(
                LocaleCode.of("en_gb"),
                TestMessage.HELLO,
                MessageArguments.of("name", "A\u00A7c\nB")
        );
        assertEquals(
                Arrays.asList("<green>+ <white>Hello A c B"),
                rendered
        );
        assertFalse(rendered.get(0).contains("\n"));
    }

    /**
     * 验证缺少必需占位符时拒绝发送不完整消息。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：构建必需 name 的模板并使用空参数调用渲染。</p>
     * <p>主要调用方：JUnit。</p>
     *
     * @throws IllegalArgumentException 预期的参数契约异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectMissingRequiredArgument() {
        // 1. 复用最小目录和无颜色转换器创建消息服务
        LocaleCode english = LocaleCode.of("en_us");
        Map<String, MessageTemplate> englishTemplates =
                new LinkedHashMap<String, MessageTemplate>();
        englishTemplates.put(
                TestMessage.HELLO.getPath(),
                new MessageTemplate(
                        Collections.singletonList("Hello {name}")
                )
        );
        Map<LocaleCode, Map<String, MessageTemplate>> catalogs =
                new LinkedHashMap<LocaleCode, Map<String, MessageTemplate>>();
        catalogs.put(english, englishTemplates);
        MessageService service = new MessageService(
                new MessageCatalogSnapshot(catalogs),
                english,
                Collections.<LocaleCode, LocaleCode>emptyMap(),
                new MessageColorizer() {
                    /**
                     * 原样返回无颜色测试模板。
                     *
                     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
                     * <p>主要逻辑：不改变输入文本。</p>
                     * <p>主要调用方：MessageService。</p>
                     *
                     * @param trustedTemplate 测试模板
                     * @return 原模板
                     */
                    @Override
                    public String colorize(String trustedTemplate) {
                        // 1. 本测试只关注占位符契约
                        return trustedTemplate;
                    }
                },
                Logger.getLogger("MessageServiceTest")
        );

        // 2. 空参数不能满足 name 契约
        service.render(
                english,
                TestMessage.HELLO,
                MessageArguments.empty()
        );
    }

    /** 仅用于公共消息协议测试的业务枚举。 */
    private enum TestMessage implements MessageDefinition {
        /** 包含前缀与 name 占位符的测试消息。 */
        HELLO;

        /**
         * 获取测试消息路径。
         *
         * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
         * <p>主要逻辑：返回固定路径。</p>
         * <p>主要调用方：MessageServiceTest。</p>
         *
         * @return 测试路径
         */
        @Override
        public String getPath() {
            // 1. 单一枚举值使用单一路径
            return "test.hello";
        }

        /**
         * 获取测试前缀模板。
         *
         * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
         * <p>主要逻辑：返回带绿色标记的固定加号。</p>
         * <p>主要调用方：MessageServiceTest。</p>
         *
         * @return 测试前缀
         */
        @Override
        public String getPrefixTemplate() {
            // 1. 前缀只由代码声明
            return "&a+ ";
        }
    }
}
