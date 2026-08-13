package com.lx.mc.foundation.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 使用单个后台线程执行跨实体的异步写后持久化任务。
 *
 * <p>作者：lxcraftcm</p>
 * <p>创建时间：2026-07-31 13:04</p>
 *
 * <p>主要逻辑：</p>
 * <ol>
 *     <li>按照“命名空间 + 实体标识”登记最新持久化任务和修订号。</li>
 *     <li>在单线程执行器中串行执行阻塞 I/O，并合并尚未开始的旧版本任务。</li>
 *     <li>失败任务保留为脏数据，由定时重试或停服刷盘再次处理。</li>
 * </ol>
 *
 * <p>主要调用方：领域仓储基础设施实现和数据基础设施生命周期管理器。</p>
 * <p>约束：业务层不得直接调用本类，入队快照必须不可变。</p>
 */
public final class WriteBehindPersistenceQueue {

    /**
     * 用于保护任务映射、执行中标识、计数器和生命周期状态的监视器。
     */
    private final Object monitor;

    /**
     * 串行执行全部阻塞持久化操作的单线程执行器。
     */
    private final ExecutorService executor;

    /**
     * 输出持久化失败、重试和停服刷盘问题的项目日志。
     */
    private final Logger logger;

    /**
     * 单个持久化任务首次失败后允许再次尝试的最大次数。
     */
    private final int maxRetries;

    /**
     * 保存每个实体键当前尚未成功落盘的最新命令。
     */
    private final Map<String, PersistenceCommand> dirtyCommands;

    /**
     * 保存每个实体键曾登记过的最大修订号，防止旧任务回写。
     */
    private final Map<String, Long> latestRevisions;

    /**
     * 保存当前已经提交给执行器但尚未完成的实体键。
     */
    private final Set<String> inFlightKeys;

    /**
     * 记录包括加载任务在内的全部未完成后台任务数量。
     */
    private int pendingTasks;

    /**
     * 表示队列是否仍接受来自正常业务流程的新任务。
     */
    private boolean accepting;

    /**
     * 表示执行器是否已经进入不可再次调度的关闭阶段。
     */
    private boolean closed;

    /**
     * 创建一个具有独立名称的单线程异步持久化队列。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>校验日志、重试次数和线程名称。</li>
     *     <li>初始化任务状态容器和单线程执行器。</li>
     * </ol>
     *
     * <p>主要调用方：数据基础设施装配流程和基础设施测试。</p>
     *
     * @param logger      用于记录异步错误的日志实例，不能为空
     * @param maxRetries  单个任务失败后的最大重试次数，不能小于零
     * @param threadName  持久化工作线程名称，不能为空
     * @throws IllegalArgumentException 最大重试次数为负数时抛出
     */
    public WriteBehindPersistenceQueue(Logger logger, int maxRetries, String threadName) {
        // 1. 校验构造参数，避免后台任务启动后才暴露装配错误
        this.logger = Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(threadName, "threadName");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }

        // 2. 初始化队列状态和用于串行执行 I/O 的单线程执行器
        this.monitor = new Object();
        this.maxRetries = maxRetries;
        this.dirtyCommands = new HashMap<String, PersistenceCommand>();
        this.latestRevisions = new HashMap<String, Long>();
        this.inFlightKeys = new HashSet<String>();
        this.pendingTasks = 0;
        this.accepting = true;
        this.closed = false;
        this.executor = Executors.newSingleThreadExecutor(
                new PersistenceThreadFactory(threadName)
        );
    }

    /**
     * 在持久化工作线程执行一个非写后队列的阻塞任务。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>在队列仍可用时增加待处理任务数量。</li>
     *     <li>提交任务并通过 CompletableFuture 返回结果或异常。</li>
     *     <li>任务结束后减少计数并唤醒刷盘等待者。</li>
     * </ol>
     *
     * <p>主要调用方：插件启动目录准备和实体全量加载流程。</p>
     * <p>副作用：在持久化工作线程执行给定阻塞操作。</p>
     *
     * @param description 用于错误日志的任务说明，不能为空
     * @param action      需要在持久化线程执行的阻塞任务，不能为空
     * @param <R>         任务返回值类型
     * @return 用于观察任务完成结果的 CompletableFuture
     * @throws IllegalStateException 队列停止接收任务或已经关闭时抛出
     */
    public <R> CompletableFuture<R> submit(String description, Callable<R> action) {
        // 1. 校验任务说明和阻塞操作
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(action, "action");
        final CompletableFuture<R> future = new CompletableFuture<R>();

        // 2. 在生命周期锁内登记任务并提交给单线程执行器
        synchronized (monitor) {
            ensureAcceptingLocked();
            pendingTasks++;
            try {
                executor.execute(new Runnable() {
                    /**
                     * 执行阻塞任务并完成对应的异步结果。
                     *
                     * <p>作者：lxcraftcm</p>
                     * <p>创建时间：2026-07-31 13:04</p>
                     * <p>主要逻辑：执行任务、记录异常并释放待处理计数。</p>
                     * <p>主要调用方：单线程持久化执行器。</p>
                     */
                    @Override
                    public void run() {
                        // 1. 执行阻塞任务并将结果交给调用方
                        try {
                            future.complete(action.call());
                        } catch (Throwable throwable) {
                            logger.log(
                                    Level.SEVERE,
                                    "Persistence task failed: " + description,
                                    throwable
                            );
                            future.completeExceptionally(throwable);
                        } finally {
                            // 2. 无论成功失败都释放待处理任务计数
                            completeUnkeyedTask();
                        }
                    }
                });
            } catch (RejectedExecutionException exception) {
                // 3. 执行器拒绝任务时恢复计数并立即暴露关闭状态
                pendingTasks--;
                monitor.notifyAll();
                future.completeExceptionally(exception);
                throw new IllegalStateException("Persistence queue rejected task", exception);
            }
        }
        return future;
    }

    /**
     * 在停止接收业务写入后提交关闭阶段的维护任务。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>确认执行器尚未进入最终关闭状态。</li>
     *     <li>登记并提交适配器刷新或关闭任务。</li>
     *     <li>任务结束后释放待处理计数。</li>
     * </ol>
     *
     * <p>主要调用方：DataInfrastructure.shutdown 方法。</p>
     * <p>副作用：在业务入口关闭后继续向持久化线程提交生命周期任务。</p>
     *
     * @param description 用于错误日志的维护任务说明，不能为空
     * @param action      需要在持久化线程执行的维护操作，不能为空
     * @param <R>         任务返回值类型
     * @return 用于观察维护任务完成结果的 CompletableFuture
     * @throws IllegalStateException 执行器已经进入最终关闭状态时抛出
     */
    public <R> CompletableFuture<R> submitMaintenance(
            String description,
            Callable<R> action
    ) {
        // 1. 校验维护任务说明和具体操作
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(action, "action");
        final CompletableFuture<R> future = new CompletableFuture<R>();

        // 2. 在状态锁内确认执行器仍可用，并登记维护任务
        synchronized (monitor) {
            ensureOpenLocked();
            pendingTasks++;
            try {
                executor.execute(new Runnable() {
                    /**
                     * 执行关闭阶段的适配器维护操作。
                     *
                     * <p>作者：lxcraftcm</p>
                     * <p>创建时间：2026-07-31 13:04</p>
                     * <p>主要逻辑：执行维护任务、传播结果并释放待处理计数。</p>
                     * <p>主要调用方：单线程持久化执行器。</p>
                     */
                    @Override
                    public void run() {
                        // 1. 执行维护操作并完成对应异步结果
                        try {
                            future.complete(action.call());
                        } catch (Throwable throwable) {
                            logger.log(
                                    Level.SEVERE,
                                    "Persistence maintenance task failed: " + description,
                                    throwable
                            );
                            future.completeExceptionally(throwable);
                        } finally {
                            // 2. 无论成功失败都释放待处理任务计数
                            completeUnkeyedTask();
                        }
                    }
                });
            } catch (RejectedExecutionException exception) {
                // 3. 执行器拒绝任务时恢复计数并暴露维护失败
                pendingTasks--;
                monitor.notifyAll();
                future.completeExceptionally(exception);
                throw new IllegalStateException(
                        "Persistence queue rejected maintenance task",
                        exception
                );
            }
        }
        return future;
    }

    /**
     * 在持久化工作线程加载指定实体类型的全部快照。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：将持久化适配器的阻塞加载操作提交到统一工作线程。</p>
     * <p>主要调用方：插件启动数据加载流程。</p>
     *
     * @param persistence 待加载的实体持久化适配器，不能为空
     * @param <T>         不可变持久化快照类型
     * @return 包含完整快照列表的 CompletableFuture
     */
    public <T extends PersistedRecord> CompletableFuture<List<T>> loadAll(
            final EntityPersistence<T> persistence
    ) {
        // 1. 校验适配器并提交阻塞加载任务
        Objects.requireNonNull(persistence, "persistence");
        return submit(
                "load all records from " + persistence.getNamespace(),
                new Callable<List<T>>() {
                    /**
                     * 调用实体适配器读取全部快照。
                     *
                     * <p>作者：lxcraftcm</p>
                     * <p>创建时间：2026-07-31 13:04</p>
                     * <p>主要逻辑：执行适配器加载并复制返回列表。</p>
                     * <p>主要调用方：持久化工作线程。</p>
                     *
                     * @return 与本次加载结果对应的独立列表
                     * @throws Exception 持久化适配器加载失败时抛出
                     */
                    @Override
                    public List<T> call() throws Exception {
                        // 1. 加载快照并复制结果，避免暴露适配器内部集合
                        return new ArrayList<T>(persistence.loadAll());
                    }
                }
        );
    }

    /**
     * 登记单个实体的异步写入任务。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：创建带命名空间和修订号的写入命令并登记最新版本。</p>
     * <p>主要调用方：领域仓储保存实现。</p>
     * <p>副作用：登记脏数据并可能立即调度后台写入。</p>
     *
     * @param persistence 负责实际写入的持久化适配器，不能为空
     * @param snapshot    待持久化的不可变数据快照，不能为空
     * @param <T>         不可变持久化快照类型
     * @throws IllegalArgumentException 快照修订号不是递增值时抛出
     * @throws IllegalStateException 队列停止接收新任务时抛出
     */
    public <T extends PersistedRecord> void enqueueWrite(
            EntityPersistence<T> persistence,
            T snapshot
    ) {
        // 1. 校验适配器和不可变数据快照
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(snapshot, "snapshot");

        // 2. 创建写入命令并登记为当前实体的最新脏数据
        register(PersistenceCommand.write(persistence, snapshot));
    }

    /**
     * 登记单个实体的异步删除任务。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：创建带命名空间和修订号的删除命令并覆盖旧写入任务。</p>
     * <p>主要调用方：领域仓储删除实现。</p>
     * <p>副作用：登记脏数据并可能立即调度后台删除。</p>
     *
     * @param persistence 负责实际删除的持久化适配器，不能为空
     * @param id          待删除记录的唯一标识，不能为空
     * @param revision    本次删除对应的递增修订号，不能小于零
     * @param <T>         不可变持久化快照类型
     * @throws IllegalArgumentException 修订号不是递增值时抛出
     * @throws IllegalStateException 队列停止接收新任务时抛出
     */
    public <T extends PersistedRecord> void enqueueDelete(
            EntityPersistence<T> persistence,
            String id,
            long revision
    ) {
        // 1. 校验适配器、记录标识和删除修订号
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(id, "id");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision cannot be negative");
        }

        // 2. 创建删除命令并登记为当前实体的最新脏数据
        register(PersistenceCommand.delete(persistence, id, revision));
    }

    /**
     * 登记一个与实体修订绑定的复合持久化操作。
     *
     * <p>作者：lxcraftcm</p><p>创建时间：2026-08-02</p>
     * <p>主要逻辑：使用与写入、删除相同的实体键合并、失败重试和只读诊断协议。</p>
     * <p>主要调用方：共享箱子管理员归档流程。</p>
     *
     * @param namespace 实体持久化命名空间
     * @param id 实体稳定记录标识
     * @param revision 严格递增的操作修订
     * @param description 日志说明
     * @param operation 实际阻塞式复合操作
     */
    public void enqueueOperation(
            String namespace,
            String id,
            long revision,
            String description,
            PersistenceOperation operation
    ) {
        // 1. 自定义操作仍必须携带合法非负修订和明确日志说明
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(description, "description");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision cannot be negative");
        }

        // 2. 复用统一登记流程覆盖尚未执行的同实体旧写入
        register(PersistenceCommand.operation(
                namespace,
                id,
                revision,
                description,
                operation
        ));
    }

    /**
     * 重新调度当前仍处于脏状态且没有执行中任务的数据。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：遍历脏数据并为没有执行中任务的实体重新提交最新命令。</p>
     * <p>主要调用方：业务插件定时重试任务和停用刷盘流程。</p>
     * <p>副作用：可能向持久化执行器提交多个重试任务。</p>
     */
    public void retryDirty() {
        // 1. 在队列状态锁内为全部未执行脏数据尝试重新调度
        synchronized (monitor) {
            for (String key : new ArrayList<String>(dirtyCommands.keySet())) {
                scheduleIfIdleLocked(key);
            }
        }
    }

    /**
     * 等待当前任务完成，并确认不存在尚未落盘的脏数据。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>重新调度当前脏数据。</li>
     *     <li>在指定超时时间内等待后台任务全部结束。</li>
     *     <li>根据待处理计数和脏数据数量判断刷盘结果。</li>
     * </ol>
     *
     * <p>主要调用方：插件停用流程和基础设施测试。</p>
     *
     * @param timeout 最大等待时长，不能小于零
     * @param unit    等待时长单位，不能为空
     * @return 所有任务完成且没有脏数据时返回 true
     * @throws InterruptedException 当前线程等待期间被中断时抛出
     */
    public boolean flush(long timeout, TimeUnit unit) throws InterruptedException {
        // 1. 校验等待参数并计算单调时钟截止时间
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);

        // 2. 重新调度脏数据并等待当前后台任务结束
        synchronized (monitor) {
            for (String key : new ArrayList<String>(dirtyCommands.keySet())) {
                scheduleIfIdleLocked(key);
            }
            waitForPendingTasksLocked(deadlineNanos);

            // 3. 仅在任务清空且不存在失败脏数据时报告刷盘成功
            return pendingTasks == 0 && dirtyCommands.isEmpty();
        }
    }

    /**
     * 停止接受来自正常业务流程的新持久化任务。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：关闭入队开关，同时保留已有脏数据的刷盘能力。</p>
     * <p>主要调用方：插件停用流程。</p>
     * <p>副作用：后续普通提交和入队操作将被拒绝。</p>
     */
    public void stopAccepting() {
        // 1. 在生命周期锁内关闭正常业务任务入口
        synchronized (monitor) {
            accepting = false;
        }
    }

    /**
     * 在指定时间内刷盘并关闭持久化工作线程。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>停止接收新任务并尝试刷完脏数据。</li>
     *     <li>关闭执行器并等待工作线程退出。</li>
     *     <li>超时后中断残余任务并返回失败结果。</li>
     * </ol>
     *
     * <p>主要调用方：数据基础设施停用流程。</p>
     * <p>副作用：永久关闭当前队列及其工作线程。</p>
     *
     * @param timeout 最大关闭等待时长，不能小于零
     * @param unit    等待时长单位，不能为空
     * @return 脏数据全部刷盘且工作线程正常结束时返回 true
     * @throws InterruptedException 当前线程等待期间被中断时抛出
     */
    public boolean shutdown(long timeout, TimeUnit unit) throws InterruptedException {
        // 1. 校验参数、停止接收新任务并记录统一截止时间
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        long timeoutNanos = unit.toNanos(timeout);
        long deadlineNanos = System.nanoTime() + timeoutNanos;
        stopAccepting();

        // 2. 在剩余时间内尝试刷完所有已登记任务
        boolean flushed = flushRemainingUntil(deadlineNanos);

        // 3. 使用剩余时间终止执行器并等待工作线程退出
        long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
        boolean terminated = terminate(remainingNanos, TimeUnit.NANOSECONDS);
        return flushed && terminated;
    }

    /**
     * 不再重新调度脏数据，直接有序关闭持久化执行器。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>禁止后续任何任务调度并请求执行器有序关闭。</li>
     *     <li>在指定时间内等待已提交任务完成。</li>
     *     <li>超时后中断残余任务。</li>
     * </ol>
     *
     * <p>主要调用方：DataInfrastructure.shutdown 和 shutdown 方法。</p>
     * <p>副作用：永久关闭当前持久化工作线程。</p>
     *
     * @param timeout 最大终止等待时长，不能小于零
     * @param unit    等待时长单位，不能为空
     * @return 已提交任务全部完成且工作线程正常退出时返回 true
     * @throws InterruptedException 当前线程等待期间被中断时抛出
     */
    public boolean terminate(long timeout, TimeUnit unit) throws InterruptedException {
        // 1. 校验等待参数并禁止后续任何任务调度
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        synchronized (monitor) {
            accepting = false;
            closed = true;
        }
        executor.shutdown();

        // 2. 在指定时间内等待工作线程完成全部已提交任务
        boolean terminated = executor.awaitTermination(timeout, unit);
        if (!terminated) {
            // 3. 超时后中断残余任务，避免插件停用流程无限阻塞
            executor.shutdownNow();
        }
        return terminated;
    }

    /**
     * 获取当前尚未成功持久化的实体数量。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：在状态锁内读取脏任务映射大小。</p>
     * <p>主要调用方：运行状态监控和测试断言。</p>
     *
     * @return 当前脏实体数量
     */
    public int getDirtyCount() {
        // 1. 在状态锁内返回尚未成功落盘的实体数量
        synchronized (monitor) {
            return dirtyCommands.size();
        }
    }

    /**
     * 获取已经结束本轮执行且仍未成功持久化的命令数量。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-08-10</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>遍历当前仍然保留的最新脏命令。</li>
     *     <li>排除正在正常执行或重试中的命令。</li>
     *     <li>只统计已经耗尽本轮重试并等待后续恢复调度的命令。</li>
     * </ol>
     *
     * <p>主要调用方：DataInfrastructure.retryDirty。</p>
     *
     * @return 当前已经确认失败并处于空闲等待状态的命令数量
     */
    public int getFailedCommandCount() {
        // 1. 命令映射与执行中集合必须在同一状态锁内形成一致快照
        synchronized (monitor) {
            int failedCount = 0;
            for (String key : dirtyCommands.keySet()) {
                if (!inFlightKeys.contains(key)) {
                    failedCount++;
                }
            }
            return failedCount;
        }
    }

    /**
     * 获取当前尚未完成的后台任务数量。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：在状态锁内读取加载和持久化任务总数。</p>
     * <p>主要调用方：运行状态监控和测试断言。</p>
     *
     * @return 当前待处理任务数量
     */
    public int getPendingTaskCount() {
        // 1. 在状态锁内返回尚未完成的后台任务数量
        synchronized (monitor) {
            return pendingTasks;
        }
    }

    /**
     * 判断指定实体是否保留一条已经结束当前尝试的失败脏命令。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-08-02</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>使用与写后队列相同的命名空间和记录 ID 组合键。</li>
     *     <li>脏命令存在且当前没有执行中任务时，视为本轮已最终失败。</li>
     *     <li>新入队或正在执行的正常写入不会被误判为故障。</li>
     * </ol>
     *
     * <p>主要调用方：共享箱子仓储的只读保护查询。</p>
     *
     * @param namespace 持久化适配器命名空间
     * @param id 实体稳定记录 ID
     * @return 该实体存在等待后续重试的失败脏命令时返回 true
     */
    public boolean hasFailedCommand(String namespace, String id) {
        // 1. 使用与 PersistenceCommand 一致的内部实体键协议
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(id, "id");
        String key = namespace + '\0' + id;

        // 2. 在队列监视器内原子区分执行中与已失败脏数据
        synchronized (monitor) {
            return dirtyCommands.containsKey(key)
                    && !inFlightKeys.contains(key);
        }
    }

    /**
     * 登记最新实体命令并在实体空闲时提交后台执行。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>检查队列生命周期和实体修订号递增约束。</li>
     *     <li>覆盖该实体尚未执行的旧命令并保存最大修订号。</li>
     *     <li>实体没有执行中命令时立即调度最新版本。</li>
     * </ol>
     *
     * <p>主要调用方：enqueueWrite 和 enqueueDelete 方法。</p>
     * <p>副作用：修改脏数据状态并可能提交后台任务。</p>
     *
     * @param command 待登记的不可变持久化命令
     */
    private void register(PersistenceCommand command) {
        // 1. 在状态锁内检查队列生命周期和修订号顺序
        synchronized (monitor) {
            ensureAcceptingLocked();
            Long latestRevision = latestRevisions.get(command.key);
            if (latestRevision != null && command.revision <= latestRevision.longValue()) {
                throw new IllegalArgumentException(
                        "Persistence revision must increase for " + command.key
                );
            }

            // 2. 保存最大修订号，并用新命令覆盖尚未执行的旧命令
            latestRevisions.put(command.key, command.revision);
            dirtyCommands.put(command.key, command);

            // 3. 当前实体没有执行中命令时立即提交最新版本
            if (!scheduleIfIdleLocked(command.key)) {
                throw new IllegalStateException(
                        "Cannot schedule persistence command: " + command.description
                );
            }
        }
    }

    /**
     * 为指定实体键提交当前最新命令，已有执行中任务时保持原状态。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：检查关闭和执行中状态，登记计数后提交工作线程。</p>
     * <p>主要调用方：任务登记、失败重试和旧任务完成处理流程。</p>
     * <p>副作用：可能向单线程执行器提交持久化任务。</p>
     *
     * @param key 待调度实体的内部组合键
     * @return 已有任务执行中或成功提交任务时返回 true，无法提交时返回 false
     */
    private boolean scheduleIfIdleLocked(final String key) {
        // 1. 已关闭、没有脏数据或实体已有执行中任务时无需重复提交
        if (closed) {
            return false;
        }
        final PersistenceCommand command = dirtyCommands.get(key);
        if (command == null || inFlightKeys.contains(key)) {
            return true;
        }

        // 2. 标记实体正在执行并增加全局待处理任务计数
        inFlightKeys.add(key);
        pendingTasks++;
        try {
            executor.execute(new Runnable() {
                /**
                 * 在持久化工作线程执行当前实体命令。
                 *
                 * <p>作者：lxcraftcm</p>
                 * <p>创建时间：2026-07-31 13:04</p>
                 * <p>主要逻辑：跳过过期命令或按重试策略执行当前命令。</p>
                 * <p>主要调用方：单线程持久化执行器。</p>
                 */
                @Override
                public void run() {
                    // 1. 执行命令并将最终结果交回队列状态管理逻辑
                    runCommand(command);
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            // 3. 执行器拒绝任务时恢复执行中状态，同时保留脏命令等待后续处理
            inFlightKeys.remove(key);
            pendingTasks--;
            monitor.notifyAll();
            logger.log(
                    Level.SEVERE,
                    "Persistence executor rejected task: " + command.description,
                    exception
            );
            return false;
        }
    }

    /**
     * 执行单个持久化命令并更新对应脏数据状态。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>执行前检查命令是否已经被更新版本替代。</li>
     *     <li>对当前命令执行有限次数重试。</li>
     *     <li>清理成功命令或保留失败脏数据，并调度后续新版本。</li>
     * </ol>
     *
     * <p>主要调用方：持久化执行器中的实体任务。</p>
     * <p>副作用：执行阻塞 I/O 并修改队列脏数据状态。</p>
     *
     * @param command 当前执行器准备处理的不可变持久化命令
     */
    private void runCommand(PersistenceCommand command) {
        // 1. 命令开始前检查它是否已被尚未执行的新版本替代
        boolean current;
        synchronized (monitor) {
            current = dirtyCommands.get(command.key) == command;
        }

        // 2. 仅对当前最新命令执行实际持久化和有限次数重试
        boolean success = current && executeWithRetries(command);

        // 3. 更新执行中和脏数据状态，并在需要时调度更新版本
        synchronized (monitor) {
            PersistenceCommand latestCommand = dirtyCommands.get(command.key);
            if (success && latestCommand == command) {
                dirtyCommands.remove(command.key);
            }
            inFlightKeys.remove(command.key);
            pendingTasks--;

            if (latestCommand != null && latestCommand != command) {
                scheduleIfIdleLocked(command.key);
            }
            monitor.notifyAll();
        }
    }

    /**
     * 按配置的最大重试次数执行单个持久化命令。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：循环执行命令，记录中间失败，并在最终失败后保留错误日志。</p>
     * <p>主要调用方：runCommand 方法。</p>
     * <p>副作用：执行文件或数据库 I/O，并可能重复尝试同一幂等操作。</p>
     *
     * @param command 待执行的不可变持久化命令
     * @return 任意一次尝试成功时返回 true
     */
    private boolean executeWithRetries(PersistenceCommand command) {
        // 1. 执行首次尝试以及配置允许的后续重试
        int totalAttempts = maxRetries + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                command.execute();
                return true;
            } catch (Exception exception) {
                // 2. 中间失败记录警告，最终失败记录严重错误并保留脏数据
                if (attempt < totalAttempts) {
                    logger.log(
                            Level.WARNING,
                            "Persistence attempt "
                                    + attempt
                                    + " failed, retrying: "
                                    + command.description,
                            exception
                    );
                } else {
                    logger.log(
                            Level.SEVERE,
                            "Persistence task failed after "
                                    + totalAttempts
                                    + " attempts: "
                                    + command.description,
                            exception
                    );
                }
            }
        }

        // 3. 所有尝试均失败时报告未成功，调用方将保留脏命令
        return false;
    }

    /**
     * 完成一个不绑定实体键的后台任务并唤醒等待线程。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：安全减少待处理计数并通知刷盘等待者。</p>
     * <p>主要调用方：submit 方法创建的后台任务。</p>
     * <p>副作用：修改全局待处理任务数量。</p>
     */
    private void completeUnkeyedTask() {
        // 1. 在状态锁内释放一个后台任务计数并通知等待者
        synchronized (monitor) {
            pendingTasks--;
            monitor.notifyAll();
        }
    }

    /**
     * 确认队列仍允许正常业务提交新任务。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：检查接收入队标志和最终关闭标志。</p>
     * <p>主要调用方：普通任务提交和实体命令登记流程。</p>
     *
     * @throws IllegalStateException 队列已经停止接收任务时抛出
     */
    private void ensureAcceptingLocked() {
        // 1. 队列停止或关闭后拒绝任何新的普通业务任务
        if (!accepting || closed) {
            throw new IllegalStateException("Persistence queue is not accepting new tasks");
        }
    }

    /**
     * 确认持久化执行器尚未进入最终关闭状态。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：只检查最终关闭标志，允许停服阶段提交维护任务。</p>
     * <p>主要调用方：submitMaintenance 方法。</p>
     *
     * @throws IllegalStateException 执行器已经进入最终关闭状态时抛出
     */
    private void ensureOpenLocked() {
        // 1. 最终关闭后拒绝包括生命周期维护在内的全部新任务
        if (closed) {
            throw new IllegalStateException("Persistence queue is closed");
        }
    }

    /**
     * 在调用方持有状态锁时等待待处理任务结束或到达截止时间。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：根据单调时钟计算剩余时间，并循环等待任务完成通知。</p>
     * <p>主要调用方：flush 和 shutdown 方法。</p>
     *
     * @param deadlineNanos 基于 System.nanoTime 的等待截止时间
     * @throws InterruptedException 当前线程等待期间被中断时抛出
     */
    private void waitForPendingTasksLocked(long deadlineNanos) throws InterruptedException {
        // 1. 在任务未清空时根据单调时钟持续计算剩余等待时间
        while (pendingTasks > 0) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return;
            }

            // 2. 将剩余纳秒拆分为 Object.wait 支持的毫秒和纳秒部分
            long waitMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            int waitNanos = (int) (
                    remainingNanos - TimeUnit.MILLISECONDS.toNanos(waitMillis)
            );
            monitor.wait(waitMillis, waitNanos);
        }
    }

    /**
     * 使用统一截止时间完成停服阶段的最终脏数据刷盘。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：调度全部脏命令、等待任务结束并检查最终脏数据数量。</p>
     * <p>主要调用方：shutdown 方法。</p>
     *
     * @param deadlineNanos 基于 System.nanoTime 的关闭截止时间
     * @return 全部任务完成且没有脏数据时返回 true
     * @throws InterruptedException 当前线程等待期间被中断时抛出
     */
    private boolean flushRemainingUntil(long deadlineNanos) throws InterruptedException {
        // 1. 在状态锁内调度全部脏命令并等待剩余任务
        synchronized (monitor) {
            for (String key : new ArrayList<String>(dirtyCommands.keySet())) {
                scheduleIfIdleLocked(key);
            }
            waitForPendingTasksLocked(deadlineNanos);

            // 2. 根据任务计数和脏数据状态判断最终刷盘结果
            return pendingTasks == 0 && dirtyCommands.isEmpty();
        }
    }

    /**
     * 封装单个实体的一次写入或删除操作及其顺序元数据。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     *
     * <p>主要逻辑：</p>
     * <ol>
     *     <li>保存实体组合键、修订号和日志说明。</li>
     *     <li>通过统一操作接口执行具体持久化调用。</li>
     * </ol>
     *
     * <p>主要调用方：WriteBehindPersistenceQueue 内部任务管理逻辑。</p>
     * <p>约束：命令对象创建后不可修改。</p>
     */
    private static final class PersistenceCommand {

        /**
         * 由实体命名空间和记录标识组成的队列内部唯一键。
         */
        private final String key;

        /**
         * 用于比较异步任务新旧顺序的单调递增修订号。
         */
        private final long revision;

        /**
         * 用于持久化失败日志的可读任务说明。
         */
        private final String description;

        /**
         * 实际调用持久化适配器的阻塞操作。
         */
        private final PersistenceAction action;

        /**
         * 创建一个不可变持久化命令。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：保存已经完成校验的任务元数据和阻塞操作。</p>
         * <p>主要调用方：write 和 delete 工厂方法。</p>
         *
         * @param key         队列内部实体唯一键
         * @param revision    本次操作对应的修订号
         * @param description 用于错误日志的任务说明
         * @param action      实际执行的持久化操作
         */
        private PersistenceCommand(
                String key,
                long revision,
                String description,
                PersistenceAction action
        ) {
            // 1. 保存不可变的任务顺序信息、日志说明和执行操作
            this.key = key;
            this.revision = revision;
            this.description = description;
            this.action = action;
        }

        /**
         * 为单个不可变快照创建写入命令。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：组合实体键，并绑定适配器的 write 调用。</p>
         * <p>主要调用方：enqueueWrite 方法。</p>
         *
         * @param persistence 负责实际写入的适配器
         * @param snapshot    待写入的不可变数据快照
         * @param <T>         不可变持久化快照类型
         * @return 包含写入操作的不可变命令
         */
        private static <T extends PersistedRecord> PersistenceCommand write(
                final EntityPersistence<T> persistence,
                final T snapshot
        ) {
            // 1. 校验快照修订号满足持久化顺序要求
            if (snapshot.getRevision() < 0L) {
                throw new IllegalArgumentException("revision cannot be negative");
            }

            // 2. 组合实体键并绑定具体适配器写入操作
            String key = buildKey(persistence.getNamespace(), snapshot.getId());
            return new PersistenceCommand(
                    key,
                    snapshot.getRevision(),
                    "write " + key + " at revision " + snapshot.getRevision(),
                    new PersistenceAction() {
                        /**
                         * 将命令持有的不可变快照写入持久化适配器。
                         *
                         * <p>作者：lxcraftcm</p>
                         * <p>创建时间：2026-07-31 13:04</p>
                         * <p>主要逻辑：调用适配器 write 方法。</p>
                         * <p>主要调用方：PersistenceCommand.execute 方法。</p>
                         *
                         * @throws Exception 持久化适配器写入失败时抛出
                         */
                        @Override
                        public void execute() throws Exception {
                            // 1. 将不可变快照写入具体持久化适配器
                            persistence.write(snapshot);
                        }
                    }
            );
        }

        /**
         * 为指定实体标识创建删除命令。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：组合实体键，并绑定适配器的 delete 调用。</p>
         * <p>主要调用方：enqueueDelete 方法。</p>
         *
         * @param persistence 负责实际删除的适配器
         * @param id          待删除记录唯一标识
         * @param revision    本次删除对应的修订号
         * @param <T>         不可变持久化快照类型
         * @return 包含删除操作的不可变命令
         */
        private static <T extends PersistedRecord> PersistenceCommand delete(
                final EntityPersistence<T> persistence,
                final String id,
                long revision
        ) {
            // 1. 组合实体键并绑定具体适配器删除操作
            String key = buildKey(persistence.getNamespace(), id);
            return new PersistenceCommand(
                    key,
                    revision,
                    "delete " + key + " at revision " + revision,
                    new PersistenceAction() {
                        /**
                         * 删除命令指定的持久化记录。
                         *
                         * <p>作者：lxcraftcm</p>
                         * <p>创建时间：2026-07-31 13:04</p>
                         * <p>主要逻辑：调用适配器 delete 方法。</p>
                         * <p>主要调用方：PersistenceCommand.execute 方法。</p>
                         *
                         * @throws Exception 持久化适配器删除失败时抛出
                         */
                        @Override
                        public void execute() throws Exception {
                            // 1. 删除指定标识对应的持久化记录
                            persistence.delete(id);
                        }
                    }
            );
        }

        /** 为一个实体创建可重试的复合持久化命令。 */
        private static PersistenceCommand operation(
                String namespace,
                String id,
                long revision,
                String description,
                final PersistenceOperation operation
        ) {
            // 1. 复合操作使用同一实体键，因此可以覆盖尚未执行的旧写入
            String key = buildKey(namespace, id);
            return new PersistenceCommand(
                    key,
                    revision,
                    description + " " + key + " at revision " + revision,
                    new PersistenceAction() {
                        @Override
                        public void execute() throws Exception {
                            // 1. 在专用持久化线程执行调用方提供的阻塞操作
                            operation.execute();
                        }
                    }
            );
        }

        /**
         * 执行当前命令绑定的具体持久化操作。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：调用不可变命令持有的持久化操作。</p>
         * <p>主要调用方：executeWithRetries 方法。</p>
         *
         * @throws Exception 底层持久化操作失败时抛出
         */
        private void execute() throws Exception {
            // 1. 执行命令创建阶段绑定的持久化操作
            action.execute();
        }

        /**
         * 使用命名空间和实体标识构建队列内部唯一键。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：校验两个键部分后使用不可出现在命名空间中的分隔符组合。</p>
         * <p>主要调用方：write 和 delete 工厂方法。</p>
         *
         * @param namespace 实体持久化命名空间
         * @param id        实体唯一标识
         * @return 队列内部跨实体类型唯一的组合键
         */
        private static String buildKey(String namespace, String id) {
            // 1. 校验组合键的两个组成部分
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(id, "id");
            if (namespace.isEmpty() || id.isEmpty()) {
                throw new IllegalArgumentException("namespace and id cannot be empty");
            }

            // 2. 使用空字符分隔命名空间和标识，避免普通文本连接冲突
            return namespace + '\0' + id;
        }
    }

    /**
     * 定义单个持久化命令可以抛出异常的阻塞操作。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：统一写入和删除命令的执行签名。</p>
     * <p>主要调用方：PersistenceCommand。</p>
     */
    private interface PersistenceAction {

        /**
         * 执行当前命令绑定的阻塞式持久化操作。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：调用具体持久化适配器方法。</p>
         * <p>主要调用方：PersistenceCommand.execute 方法。</p>
         *
         * @throws Exception 底层持久化操作失败时抛出
         */
        void execute() throws Exception;
    }

    /**
     * 为持久化执行器创建具有固定名称的守护线程。
     *
     * <p>作者：lxcraftcm</p>
     * <p>创建时间：2026-07-31 13:04</p>
     * <p>主要逻辑：创建单个低优先级守护线程并设置可识别名称。</p>
     * <p>主要调用方：Executors.newSingleThreadExecutor。</p>
     */
    private static final class PersistenceThreadFactory implements ThreadFactory {

        /**
         * 赋予持久化工作线程的稳定名称。
         */
        private final String threadName;

        /**
         * 创建持有指定线程名称的线程工厂。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：校验并保存持久化线程名称。</p>
         * <p>主要调用方：WriteBehindPersistenceQueue 构造方法。</p>
         *
         * @param threadName 持久化工作线程名称
         */
        private PersistenceThreadFactory(String threadName) {
            // 1. 保存已经由外部构造流程校验的线程名称
            this.threadName = threadName;
        }

        /**
         * 创建执行持久化任务的后台守护线程。
         *
         * <p>作者：lxcraftcm</p>
         * <p>创建时间：2026-07-31 13:04</p>
         * <p>主要逻辑：创建线程并设置名称、守护状态和较低优先级。</p>
         * <p>主要调用方：ExecutorService。</p>
         *
         * @param runnable 执行器提供的工作循环
         * @return 已完成基础属性设置的新线程
         */
        @Override
        public Thread newThread(Runnable runnable) {
            // 1. 创建具有稳定名称的后台线程
            Thread thread = new Thread(runnable, threadName);

            // 2. 设置守护状态和较低优先级，避免阻止服务端进程退出
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
