package com.lx.mc.foundation.lifecycle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证公共数据运行时的初始化、就绪与关闭状态迁移。
 *
 * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
 * <p>主要逻辑：使用临时目录启动真实异步队列，并确认关闭后状态与目录存在。</p>
 * <p>主要调用方：Maven Surefire。</p>
 */
public final class DataRuntimeTest {

    /** 每个测试独立使用的文件系统目录。 */
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    /**
     * 验证完整数据运行时生命周期。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-13</p>
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>创建尚不存在的数据子目录。</li>
     *     <li>等待异步初始化并发布 READY。</li>
     *     <li>关闭队列并确认 STOPPED。</li>
     * </ol>
     * <p>主要调用方：JUnit。</p>
     *
     * @throws Exception 临时目录或异步等待失败时向测试框架传播
     */
    @Test
    public void shouldInitializeAndShutdownIndependentRuntime()
            throws Exception {
        // 1. 使用尚未创建的子目录验证真实目录准备任务
        Path dataDirectory = temporaryFolder.getRoot()
                .toPath()
                .resolve("data");
        DataRuntime runtime = new DataRuntime(
                dataDirectory,
                Logger.getLogger("DataRuntimeTest"),
                1,
                "DataRuntimeTest-Persistence"
        );
        assertEquals(DataRuntimeState.INITIALIZING, runtime.getState());

        // 2. 异步目录准备完成后由所属主线程显式发布就绪状态
        runtime.initializeAsync().get(3L, TimeUnit.SECONDS);
        runtime.markReady();
        assertTrue(dataDirectory.toFile().isDirectory());
        assertEquals(DataRuntimeState.READY, runtime.getState());
        assertTrue(runtime.getState().isBusinessAvailable());

        // 3. 空队列可在超时内完成关闭并发布最终状态
        assertTrue(runtime.shutdown(3L, TimeUnit.SECONDS));
        assertEquals(DataRuntimeState.STOPPED, runtime.getState());
    }
}
