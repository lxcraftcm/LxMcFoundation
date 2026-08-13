package com.lx.mc.foundation.cache;

import org.junit.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 验证内存缓存的基础增删改查、整体替换和快照隔离行为。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 13:04</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>验证单实体写入、查询和删除。</li>
 *     <li>验证启动数据整体替换和只读快照。</li>
 * </ol>
 *
 * <p>主要调用方：Maven Surefire 测试执行器。</p>
 */
public final class InMemoryCacheStoreTest {

    /**
     * 验证缓存可以写入、覆盖、查询和删除单个实体。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>写入并查询初始值。</li>
     *     <li>覆盖同一键并验证实体数量不变。</li>
     *     <li>删除实体并确认缓存为空。</li>
     * </ol>
     *
     * <p>主要调用方：JUnit 测试执行器。</p>
     */
    @Test
    public void shouldStoreReplaceAndRemoveValue() {
        // 1. 写入初始值并验证查询结果和缓存数量
        InMemoryCacheStore<String, String> cache =
                new InMemoryCacheStore<String, String>();
        cache.put("entity-1", "initial");
        assertEquals("initial", cache.find("entity-1").orElse(null));
        assertEquals(1, cache.size());

        // 2. 覆盖同一键并确认缓存只保留最新值
        cache.put("entity-1", "updated");
        assertEquals("updated", cache.find("entity-1").orElse(null));
        assertEquals(1, cache.size());

        // 3. 删除实体并确认返回原值且缓存中不再存在该键
        assertEquals("updated", cache.remove("entity-1").orElse(null));
        assertFalse(cache.find("entity-1").isPresent());
        assertEquals(0, cache.size());
    }

    /**
     * 验证整体替换会复制输入并返回不可修改的缓存快照。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>使用启动数据整体替换缓存。</li>
     *     <li>修改原始 Map 并确认不会影响缓存。</li>
     *     <li>确认调用方不能修改返回的快照集合。</li>
     * </ol>
     *
     * <p>主要调用方：JUnit 测试执行器。</p>
     */
    @Test
    public void shouldReplaceAllWithIsolatedReadOnlySnapshot() {
        // 1. 使用包含两个实体的启动数据整体安装缓存
        InMemoryCacheStore<String, String> cache =
                new InMemoryCacheStore<String, String>();
        Map<String, String> startupValues = new LinkedHashMap<String, String>();
        startupValues.put("entity-1", "first");
        startupValues.put("entity-2", "second");
        cache.replaceAll(startupValues);

        // 2. 修改调用方原始 Map 并确认缓存仍保持安装时的数据
        startupValues.clear();
        assertEquals(2, cache.size());
        assertTrue(cache.find("entity-1").isPresent());

        // 3. 获取缓存快照并确认集合不允许调用方修改
        Collection<String> snapshot = cache.snapshot();
        assertEquals(2, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
}
