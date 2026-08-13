package com.lx.mc.foundation.bukkit.i18n;

import com.lx.mc.foundation.i18n.LocaleCode;
import com.lx.mc.foundation.i18n.MessageCatalogSnapshot;
import com.lx.mc.foundation.i18n.MessageDefinition;
import com.lx.mc.foundation.i18n.MessageTemplate;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Bukkit YAML 语言目录的内置加载、首次复制和无效覆盖回退。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：使用真实测试资源和临时外部目录验证快照加载契约。</p>
 * <p>主要调用方：Maven Surefire。</p>
 */
public final class BukkitYamlMessageCatalogLoaderTest {

    /** 每个测试使用的独立外部语言目录。 */
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    /**
     * 验证内置语言完整加载并复制到外部目录。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：加载中英资源，检查中文模板，再确认两份外部文件已创建。</p>
     * <p>主要调用方：JUnit。</p>
     *
     * @throws Exception 临时文件系统或 YAML 加载失败时向测试框架传播
     */
    @Test
    public void shouldLoadAndCopyBuiltInCatalogs() throws Exception {
        // 1. 使用空外部目录创建两种内置语言的加载器
        Path languageDirectory = temporaryFolder.newFolder("lang")
                .toPath();
        BukkitYamlMessageCatalogLoader loader = createLoader(
                languageDirectory
        );

        // 2. 快照中文模板必须来自对应内置文件
        MessageCatalogSnapshot snapshot = loader.load();
        Optional<MessageTemplate> chinese = snapshot.find(
                LocaleCode.of("zh_cn"),
                TestMessage.HELLO
        );
        assertTrue(chinese.isPresent());
        assertEquals(
                "{prefix}&f你好 {name}",
                chinese.get().getLines().get(0)
        );

        // 3. 首次加载为管理员复制可编辑的中英文件
        assertTrue(Files.isRegularFile(
                languageDirectory.resolve("en_us.yml")
        ));
        assertTrue(Files.isRegularFile(
                languageDirectory.resolve("zh_cn.yml")
        ));
    }

    /**
     * 验证占位符契约不匹配的外部覆盖不会破坏内置模板。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：预先写入缺少 name 的中文覆盖，然后确认快照仍使用内置中文。</p>
     * <p>主要调用方：JUnit。</p>
     *
     * @throws Exception 临时文件系统或 YAML 加载失败时向测试框架传播
     */
    @Test
    public void shouldIgnorePlaceholderMismatchedOverride() throws Exception {
        // 1. 外部中文覆盖故意删除 name 占位符
        Path languageDirectory = temporaryFolder.newFolder("invalid-lang")
                .toPath();
        Files.write(
                languageDirectory.resolve("zh_cn.yml"),
                Arrays.asList(
                        "test:",
                        "  # 测试占位符不匹配的外部覆盖。",
                        "  hello: \"{prefix}&f无效覆盖\""
                ),
                StandardCharsets.UTF_8
        );

        // 2. 加载器忽略单个无效覆盖并保留内置中文
        MessageCatalogSnapshot snapshot = createLoader(
                languageDirectory
        ).load();
        MessageTemplate chinese = snapshot.find(
                LocaleCode.of("zh_cn"),
                TestMessage.HELLO
        ).get();
        assertEquals(
                "{prefix}&f你好 {name}",
                chinese.getLines().get(0)
        );
    }

    /**
     * 创建当前测试共用的中英文 YAML 目录加载器。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：使用测试类加载器、HELLO 定义和中英语言集合。</p>
     * <p>主要调用方：本类两个测试方法。</p>
     *
     * @param languageDirectory 当前测试外部语言目录
     * @return 已装配加载器
     */
    private BukkitYamlMessageCatalogLoader createLoader(
            Path languageDirectory
    ) {
        // 1. 两种内置语言共享同一份强类型消息定义
        return new BukkitYamlMessageCatalogLoader(
                getClass().getClassLoader(),
                languageDirectory,
                Arrays.asList(TestMessage.values()),
                Arrays.asList(
                        LocaleCode.of("en_us"),
                        LocaleCode.of("zh_cn")
                ),
                Logger.getLogger("BukkitYamlMessageCatalogLoaderTest")
        );
    }

    /** 测试业务插件的强类型消息定义。 */
    private enum TestMessage implements MessageDefinition {
        /** 包含前缀和 name 占位符的测试消息。 */
        HELLO;

        /**
         * 获取 YAML 测试消息路径。
         *
         * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
         * <p>主要逻辑：返回固定路径。</p>
         * <p>主要调用方：BukkitYamlMessageCatalogLoaderTest。</p>
         *
         * @return 测试消息路径
         */
        @Override
        public String getPath() {
            // 1. 单一测试消息使用单一路径
            return "test.hello";
        }

        /**
         * 获取 YAML 测试消息前缀。
         *
         * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
         * <p>主要逻辑：返回空前缀，本测试只关注 YAML 契约。</p>
         * <p>主要调用方：BukkitYamlMessageCatalogLoaderTest。</p>
         *
         * @return 空前缀模板
         */
        @Override
        public String getPrefixTemplate() {
            // 1. 前缀不影响占位符集合校验
            return "";
        }
    }
}
