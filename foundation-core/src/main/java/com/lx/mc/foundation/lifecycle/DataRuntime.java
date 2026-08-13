package com.lx.mc.foundation.lifecycle;

import com.lx.mc.foundation.persistence.EntityPersistence;
import com.lx.mc.foundation.persistence.PersistedRecord;
import com.lx.mc.foundation.persistence.WriteBehindPersistenceQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 统一管理数据目录、持久化队列、适配器和数据生命周期状态。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 13:04</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>在后台工作线程准备业务数据目录。</li>
 *     <li>登记并向后续领域仓储提供统一持久化队列。</li>
 *     <li>在插件停用时停止新任务、刷盘并关闭适配器。</li>
 * </ol>
 *
 * <p>主要调用方：业务插件启动类和具体数据仓储装配流程。</p>
 * <p>约束：状态切换由所属插件的主线程串行调用。</p>
 */
public final class DataRuntime {

    /**
     * 当前业务插件全部持久化数据所在的规范化根目录。
     */
    private final Path dataDirectory;

    /**
     * 跨实体共用的单线程异步写后持久化队列。
     */
    private final WriteBehindPersistenceQueue persistenceQueue;

    /**
     * 需要在插件关闭阶段刷新并关闭的持久化适配器。
     */
    private final List<EntityPersistence<?>> registeredPersistences;

    /**
     * 用于记录初始化、降级和关闭错误的插件日志。
     */
    private final Logger logger;

    /**
     * 当前数据基础设施的可观察生命周期状态。
     */
    private volatile DataRuntimeState state;

    /**
     * 创建尚未初始化的数据基础设施。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>保存数据目录与日志。</li>
     *     <li>按配置创建单线程写后持久化队列。</li>
     *     <li>将初始状态设置为 INITIALIZING。</li>
     * </ol>
     *
     * <p>主要调用方：业务插件启动流程。</p>
     *
     * @param dataDirectory 业务数据根目录，不能为空
     * @param logger        插件日志实例，不能为空
     * @param maxRetries    单个持久化任务失败后的最大重试次数
     * @param threadName    当前插件持久化工作线程名称
     */
    public DataRuntime(
            Path dataDirectory,
            Logger logger,
            int maxRetries,
            String threadName
    ) {
        // 1. 校验并保存业务数据目录与日志实例
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.logger = Objects.requireNonNull(logger, "logger");

        // 2. 创建跨实体共用的单线程写后持久化队列
        this.persistenceQueue = new WriteBehindPersistenceQueue(
                logger,
                maxRetries,
                Objects.requireNonNull(threadName, "threadName")
        );

        // 3. 初始化适配器注册表和数据生命周期状态
        this.registeredPersistences = new ArrayList<EntityPersistence<?>>();
        this.state = DataRuntimeState.INITIALIZING;
    }

    /**
     * 在持久化工作线程准备业务数据根目录。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：提交目录创建任务，并由调用方在所属平台主线程确认初始化结果。</p>
     * <p>主要调用方：业务插件启动流程。</p>
     * <p>副作用：可能在插件目录内创建业务数据目录。</p>
     *
     * @return 用于观察目录准备结果的 CompletableFuture
     * @throws IllegalStateException 当前状态不是 INITIALIZING 时抛出
     */
    public CompletableFuture<Void> initializeAsync() {
        // 1. 仅允许处于初始化状态的数据基础设施开始目录准备
        requireState(DataRuntimeState.INITIALIZING);

        // 2. 将可能阻塞的目录创建提交给持久化工作线程
        return persistenceQueue.submit(
                "prepare data directory " + dataDirectory,
                new Callable<Void>() {
                    /**
                     * 创建业务数据根目录。
                     *
                     * <p>作者：lxcraftcm</p>
                     * <p>创建时间：2026-07-31 13:04</p>
                     * <p>主要逻辑：递归创建尚不存在的数据目录。</p>
                     * <p>主要调用方：单线程持久化执行器。</p>
                     *
                     * @return 固定返回 null
                     * @throws Exception 创建目录失败时抛出
                     */
                    @Override
                    public Void call() throws Exception {
                        // 1. 递归创建业务数据根目录，已存在时保持幂等成功
                        Files.createDirectories(dataDirectory);
                        return null;
                    }
                }
        );
    }

    /**
     * 登记一个需要统一管理生命周期的实体持久化适配器。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：在初始化阶段将适配器加入停服刷新和关闭列表。</p>
     * <p>主要调用方：具体业务实体仓储装配流程。</p>
     * <p>副作用：扩展数据基础设施管理的持久化适配器集合。</p>
     *
     * @param persistence 待登记的实体持久化适配器，不能为空
     * @param <T>         不可变持久化快照类型
     * @throws IllegalStateException 当前状态不是 INITIALIZING 时抛出
     */
    public <T extends PersistedRecord> void registerPersistence(
            EntityPersistence<T> persistence
    ) {
        // 1. 仅允许在数据基础设施正式就绪前登记适配器
        requireState(DataRuntimeState.INITIALIZING);
        Objects.requireNonNull(persistence, "persistence");

        // 2. 保存适配器供后续统一刷新和关闭
        registeredPersistences.add(persistence);
    }

    /**
     * 获取供领域仓储基础设施使用的统一异步持久化队列。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：返回当前数据基础设施唯一的持久化队列。</p>
     * <p>主要调用方：具体业务实体仓储基础设施实现。</p>
     *
     * @return 跨实体共用的异步写后持久化队列
     */
    public WriteBehindPersistenceQueue getPersistenceQueue() {
        // 1. 返回装配阶段创建的唯一持久化队列
        return persistenceQueue;
    }

    /**
     * 在所属平台主线程确认异步初始化成功并进入可用状态。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：校验当前状态后从 INITIALIZING 切换为 READY。</p>
     * <p>主要调用方：业务插件初始化完成回调。</p>
     * <p>副作用：允许后续正式业务访问数据仓储。</p>
     */
    public void markReady() {
        // 1. 仅允许成功的初始化流程将状态切换为可用
        requireState(DataRuntimeState.INITIALIZING);

        // 2. 发布 READY 状态供后续命令和事件入口判断
        state = DataRuntimeState.READY;
    }

    /**
     * 将初始化失败结果记录为不可用状态。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：切换 FAILED 状态并记录导致初始化失败的异常。</p>
     * <p>主要调用方：业务插件初始化完成回调。</p>
     * <p>副作用：禁止后续正式业务并输出严重错误日志。</p>
     *
     * @param throwable 导致初始化失败的异常，不能为空
     */
    public void markFailed(Throwable throwable) {
        // 1. 保存不可用状态，阻止后续业务误用未准备完成的数据
        Objects.requireNonNull(throwable, "throwable");
        state = DataRuntimeState.FAILED;

        // 2. 记录完整异常，便于管理员定位数据目录或适配器问题
        logger.log(Level.SEVERE, "Data infrastructure initialization failed", throwable);
    }

    /**
     * 重新调度失败脏数据并维护 READY 或 DEGRADED 状态。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：根据已确认失败命令数量更新状态，并提交等待恢复的任务。</p>
     * <p>主要调用方：业务插件注册的持久化恢复定时任务。</p>
     * <p>副作用：可能切换数据状态并提交后台重试任务。</p>
     */
    public void retryDirty() {
        // 1. 仅在正常或降级运行状态执行持久化恢复检查
        if (!state.isBusinessAvailable()) {
            return;
        }

        // 2. 只根据已结束执行的失败任务更新状态，正常异步写入不构成降级
        DataRuntimeState previousState = state;
        int failedCommandCount = persistenceQueue.getFailedCommandCount();
        DataRuntimeState nextState = failedCommandCount == 0
                ? DataRuntimeState.READY
                : DataRuntimeState.DEGRADED;
        state = nextState;

        // 3. 只在状态真正改变时记录一次诊断，避免每次玩家操作重复刷屏
        if (previousState != nextState) {
            if (nextState == DataRuntimeState.DEGRADED) {
                logger.warning(
                        "Data persistence degraded: "
                                + failedCommandCount
                                + " failed command(s), "
                                + persistenceQueue.getPendingTaskCount()
                                + " pending task(s); cached business remains "
                                + "available"
                );
            } else {
                logger.info(
                        "Data persistence recovered: 0 failed commands; "
                                + "cached business remained available"
                );
            }
        }

        // 4. 重新提交尚未执行的失败脏数据
        persistenceQueue.retryDirty();
    }

    /**
     * 获取当前数据基础设施生命周期状态。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：返回最近一次由主线程发布的生命周期状态。</p>
     * <p>主要调用方：插件主类、命令入口和状态监控。</p>
     *
     * @return 当前数据基础设施状态
     */
    public DataRuntimeState getState() {
        // 1. 返回通过 volatile 发布的当前生命周期状态
        return state;
    }

    /**
     * 获取规范化后的业务数据根目录。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：返回装配阶段确定的数据根路径。</p>
     * <p>主要调用方：具体 JSON 持久化适配器装配流程。</p>
     *
     * @return 业务数据根目录
     */
    public Path getDataDirectory() {
        // 1. 返回不会被本类修改的规范化数据目录对象
        return dataDirectory;
    }

    /**
     * 停止新任务、刷盘并关闭全部数据基础设施资源。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>切换 STOPPING 状态并在超时时间内刷完业务数据任务。</li>
     *     <li>按注册顺序提交适配器刷新与关闭任务，并终止工作线程。</li>
     *     <li>检查刷盘和适配器关闭结果后切换 STOPPED 状态。</li>
     * </ol>
     *
     * <p>主要调用方：业务插件停用流程。</p>
     * <p>副作用：关闭队列和全部持久化适配器，后续不能再次使用。</p>
     *
     * @param timeout 最大关闭等待时长，不能小于零
     * @param unit    等待时长单位，不能为空
     * @return 队列完全刷盘且全部适配器正常关闭时返回 true
     */
    public boolean shutdown(long timeout, TimeUnit unit) {
        // 1. 切换停止状态、关闭业务入口并计算统一停止截止时间
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        state = DataRuntimeState.STOPPING;
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        persistenceQueue.stopAccepting();

        // 2. 在关闭适配器之前尝试完成全部已登记的业务数据写入
        boolean flushed = false;
        try {
            flushed = persistenceQueue.flush(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrupted while flushing persistence queue", exception);
        }

        // 3. 数据写入任务结束后，在同一工作线程中按顺序刷新和关闭适配器
        List<CompletableFuture<Void>> closeResults = new ArrayList<CompletableFuture<Void>>();
        for (final EntityPersistence<?> persistence : registeredPersistences) {
            try {
                closeResults.add(persistenceQueue.submitMaintenance(
                        "flush and close " + persistence.getNamespace(),
                        new Callable<Void>() {
                            /**
                             * 刷新并关闭单个持久化适配器。
                             *
                             * <p>作者：lxcraftcm</p>
                             * <p>创建时间：2026-07-31 13:04</p>
                             * <p>主要逻辑：先刷新适配器缓冲，再关闭底层资源。</p>
                             * <p>主要调用方：持久化工作线程。</p>
                             *
                             * @return 固定返回 null
                             * @throws Exception 刷新或关闭适配器失败时抛出
                             */
                            @Override
                            public Void call() throws Exception {
                                // 1. 刷新适配器内部可能存在的待提交数据
                                persistence.flush();

                                // 2. 关闭适配器持有的文件或数据库资源
                                persistence.close();
                                return null;
                            }
                        }
                ));
            } catch (IllegalStateException exception) {
                logger.log(
                        Level.SEVERE,
                        "Cannot schedule persistence adapter shutdown",
                        exception
                );
            }
        }

        // 4. 使用初始超时的剩余时间完成维护任务并终止单线程执行器
        boolean queueStopped = false;
        try {
            long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
            queueStopped = persistenceQueue.terminate(
                    remainingNanos,
                    TimeUnit.NANOSECONDS
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrupted while stopping persistence queue", exception);
        }

        // 5. 检查全部适配器关闭结果，并发布最终停止状态
        boolean adaptersClosed = inspectCloseResults(closeResults);
        state = DataRuntimeState.STOPPED;
        return flushed && queueStopped && adaptersClosed;
    }

    /**
     * 检查异步适配器关闭任务是否全部正常完成。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：逐个读取已完成结果，记录异常并汇总成功状态。</p>
     * <p>主要调用方：shutdown 方法。</p>
     *
     * @param closeResults 适配器关闭任务结果集合
     * @return 全部关闭任务正常完成时返回 true
     */
    private boolean inspectCloseResults(List<CompletableFuture<Void>> closeResults) {
        // 1. 逐个读取关闭任务结果并累计失败状态
        boolean allClosed = true;
        for (CompletableFuture<Void> closeResult : closeResults) {
            if (!closeResult.isDone()) {
                allClosed = false;
                logger.severe("Persistence adapter shutdown task did not finish");
                continue;
            }
            try {
                closeResult.join();
            } catch (CompletionException exception) {
                allClosed = false;
                logger.log(
                        Level.SEVERE,
                        "Persistence adapter did not close cleanly",
                        exception.getCause()
                );
            }
        }

        // 2. 返回全部已登记适配器的汇总关闭结果
        return allClosed;
    }

    /**
     * 校验当前生命周期状态是否符合操作要求。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：比较实际状态与期望状态，并在不一致时拒绝操作。</p>
     * <p>主要调用方：初始化、适配器注册和状态切换方法。</p>
     *
     * @param expected 当前操作要求的唯一合法状态
     * @throws IllegalStateException 实际状态与期望状态不一致时抛出
     */
    private void requireState(DataRuntimeState expected) {
        // 1. 比较当前状态并拒绝任何非法生命周期调用顺序
        if (state != expected) {
            throw new IllegalStateException(
                    "Expected data state " + expected + " but was " + state
            );
        }
    }
}
