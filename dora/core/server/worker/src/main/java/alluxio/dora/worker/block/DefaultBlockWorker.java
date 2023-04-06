/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.dora.worker.block;

import static alluxio.dora.worker.block.BlockMetadataManager.WORKER_STORAGE_TIER_ASSOC;

import alluxio.dora.ClientContext;
import alluxio.dora.Constants;
import alluxio.dora.RuntimeConstants;
import alluxio.dora.Server;
import alluxio.dora.Sessions;
import alluxio.dora.annotation.SuppressFBWarnings;
import alluxio.dora.client.file.FileSystemContext;
import alluxio.dora.collections.PrefixList;
import alluxio.dora.conf.Configuration;
import alluxio.dora.conf.ConfigurationValueOptions;
import alluxio.dora.conf.PropertyKey;
import alluxio.dora.conf.Source;
import alluxio.dora.exception.AlluxioException;
import alluxio.dora.exception.BlockAlreadyExistsException;
import alluxio.dora.exception.BlockDoesNotExistException;
import alluxio.dora.exception.ExceptionMessage;
import alluxio.dora.exception.InvalidWorkerStateException;
import alluxio.dora.exception.WorkerOutOfSpaceException;
import alluxio.dora.exception.runtime.AlluxioRuntimeException;
import alluxio.dora.exception.runtime.ResourceExhaustedRuntimeException;
import alluxio.dora.exception.status.AlluxioStatusException;
import alluxio.dora.web.WebInterfaceAbstractMetricsServlet;
import alluxio.dora.worker.page.PagedBlockStore;
import alluxio.dora.grpc.AsyncCacheRequest;
import alluxio.dora.grpc.Block;
import alluxio.dora.grpc.BlockStatus;
import alluxio.dora.grpc.CacheRequest;
import alluxio.dora.grpc.GetConfigurationPOptions;
import alluxio.dora.grpc.GrpcService;
import alluxio.dora.grpc.ServiceType;
import alluxio.dora.grpc.UfsReadOptions;
import alluxio.dora.heartbeat.HeartbeatContext;
import alluxio.dora.heartbeat.HeartbeatExecutor;
import alluxio.dora.heartbeat.HeartbeatThread;
import alluxio.dora.metrics.MetricInfo;
import alluxio.dora.metrics.MetricKey;
import alluxio.dora.metrics.MetricsSystem;
import alluxio.dora.proto.dataserver.Protocol;
import alluxio.dora.retry.RetryUtils;
import alluxio.dora.user.ServerUserState;
import alluxio.dora.underfs.WorkerUfsManager;
import alluxio.dora.util.executor.ExecutorServiceFactories;
import alluxio.dora.util.io.FileUtils;
import alluxio.dora.wire.FileInfo;
import alluxio.dora.wire.WorkerNetAddress;
import alluxio.dora.worker.AbstractWorker;
import alluxio.dora.worker.SessionCleaner;
import alluxio.dora.worker.block.io.BlockReader;
import alluxio.dora.worker.block.io.BlockWriter;
import alluxio.dora.worker.block.meta.BlockMeta;
import alluxio.dora.worker.file.FileSystemMasterClient;
import alluxio.dora.worker.grpc.GrpcExecutors;

import com.codahale.metrics.Counter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.io.Closer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * The class is responsible for managing all top level components of the Block Worker.
 * <p>
 * This includes:
 * <p>
 * Periodic Threads: {@link BlockMasterSync} (Worker to Master continuous communication)
 * <p>
 * Logic: {@link DefaultBlockWorker} (Logic for all block related storage operations)
 */
@NotThreadSafe
public class DefaultBlockWorker extends AbstractWorker implements BlockWorker {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultBlockWorker.class);
  private static final long UFS_BLOCK_OPEN_TIMEOUT_MS =
      Configuration.getMs(PropertyKey.WORKER_UFS_BLOCK_OPEN_TIMEOUT_MS);

  /**
   * Used to close resources during stop.
   */
  private final Closer mResourceCloser = Closer.create();
  /**
   * Block master clients. commitBlock is the only reason to keep a pool of block master clients
   * on each worker. We should either improve our RPC model in the master or get rid of the
   * necessity to call commitBlock in the workers.
   */
  private final BlockMasterClientPool mBlockMasterClientPool;

  /**
   * Client for all file system master communication.
   */
  private final FileSystemMasterClient mFileSystemMasterClient;

  /**
   * Block store delta reporter for master heartbeat.
   */
  private final BlockHeartbeatReporter mHeartbeatReporter;

  /**
   * The under file system block store.
   */
  private final UnderFileSystemBlockStore mUnderFileSystemBlockStore;

  /**
   * Session metadata, used to keep track of session heartbeats.
   */
  private final Sessions mSessions;
  /**
   * Block Store manager.
   */
  private final BlockStore mBlockStore;
  /**
   * List of paths to always keep in memory.
   */
  private final PrefixList mWhitelist;

  /**
   * The worker ID for this worker. This is initialized in {@link #start(WorkerNetAddress)} and may
   * be updated by the block sync thread if the master requests re-registration.
   */
  private final AtomicReference<Long> mWorkerId;

  private final CacheRequestManager mCacheManager;
  private final FuseManager mFuseManager;

  private WorkerNetAddress mAddress;

  /**
   * Constructs a default block worker.
   *
   * @param blockMasterClientPool  a client pool for talking to the block master
   * @param fileSystemMasterClient a client for talking to the file system master
   * @param sessions               an object for tracking and cleaning up client sessions
   * @param blockStore             an Alluxio block store
   * @param workerId               worker id
   */
  @VisibleForTesting
  @Inject
  public DefaultBlockWorker(BlockMasterClientPool blockMasterClientPool,
                            FileSystemMasterClient fileSystemMasterClient, Sessions sessions,
                            BlockStore blockStore,
                            @Named("workerId") AtomicReference<Long> workerId) {
    super(ExecutorServiceFactories.fixedThreadPool("block-worker-executor", 5));
    mBlockMasterClientPool = mResourceCloser.register(blockMasterClientPool);
    mFileSystemMasterClient = mResourceCloser.register(fileSystemMasterClient);
    mHeartbeatReporter = new BlockHeartbeatReporter();
    /* Metrics reporter that listens on block events and increases metrics counters. */
    BlockMetricsReporter metricsReporter = new BlockMetricsReporter();
    mSessions = sessions;
    mBlockStore = mResourceCloser.register(blockStore);
    mWorkerId = workerId;
    mBlockStore.registerBlockStoreEventListener(mHeartbeatReporter);
    mBlockStore.registerBlockStoreEventListener(metricsReporter);
    FileSystemContext fsContext = mResourceCloser.register(
        FileSystemContext.create(ClientContext.create(Configuration.global()), this));
    mCacheManager = new CacheRequestManager(
        GrpcExecutors.CACHE_MANAGER_EXECUTOR, this, fsContext);
    mFuseManager = mResourceCloser.register(new FuseManager(fsContext));
    mWhitelist = new PrefixList(Configuration.getList(PropertyKey.WORKER_WHITELIST));
    if (mBlockStore instanceof MonoBlockStore) {
      mUnderFileSystemBlockStore =
          new UnderFileSystemBlockStore(
              ((MonoBlockStore) mBlockStore).getLocalBlockStore(),
              new WorkerUfsManager(mFileSystemMasterClient));
    } else {
      mUnderFileSystemBlockStore =
          new UnderFileSystemBlockStore(new TieredBlockStore(
              BlockMetadataManager.createBlockMetadataManager(),
              new BlockLockManager(),
              new TieredBlockReaderFactory(),
              new TieredBlockWriterFactory(),
              new TieredTempBlockMetaFactory()),
              new WorkerUfsManager(mFileSystemMasterClient));
    }
    Metrics.registerGauges(this);
  }

  /**
   * get the LocalBlockStore that manages local blocks.
   *
   * @return the LocalBlockStore that manages local blocks
   */
  @Override
  public BlockStore getBlockStore() {
    return mBlockStore;
  }

  @Override
  public BlockReader readBlockRemote(long sessionId, long blockId, long lockId)
      throws IOException, BlockDoesNotExistException, InvalidWorkerStateException {
    return mBlockStore.createBlockReader(sessionId, blockId, lockId);
  }

  @Override
  public boolean openUfsBlock(long sessionId, long blockId, Protocol.OpenUfsBlockOptions options)
      throws BlockAlreadyExistsException {
    return mUnderFileSystemBlockStore.acquireAccess(sessionId, blockId, options);
  }

  @Override
  public void closeUfsBlock(long sessionId, long blockId)
      throws BlockAlreadyExistsException, BlockDoesNotExistException, IOException,
      WorkerOutOfSpaceException {
    try {
      mUnderFileSystemBlockStore.closeReaderOrWriter(sessionId, blockId);
      if (mBlockStore.getTempBlockMeta(blockId) != null) {
        commitBlock(sessionId, blockId, false);
      }
    } finally {
      mUnderFileSystemBlockStore.releaseAccess(sessionId, blockId);
    }
  }

  @Override
  public void accessBlock(long sessionId, long blockId) throws BlockDoesNotExistException {
    mBlockStore.accessBlock(sessionId, blockId);
  }

  @Override
  public void moveBlock(long sessionId, long blockId, String tierAlias)
      throws BlockDoesNotExistException, BlockAlreadyExistsException, InvalidWorkerStateException,
      WorkerOutOfSpaceException, IOException {
    // TODO(calvin): Move this logic into BlockStore#moveBlockInternal if possible
    // Because the move operation is expensive, we first check if the operation is necessary
    BlockStoreLocation dst = BlockStoreLocation.anyDirInTier(tierAlias);
    long lockId = mBlockStore.lockBlock(sessionId, blockId);
    try {
      BlockMeta meta = mBlockStore.getBlockMeta(sessionId, blockId, lockId);
      if (meta.getBlockLocation().belongsTo(dst)) {
        return;
      }
    } finally {
      mBlockStore.unlockBlock(lockId);
    }
    // Execute the block move if necessary
    mBlockStore.moveBlock(sessionId, blockId, AllocateOptions.forMove(dst));
  }

  @Override
  public String readBlock(long sessionId, long blockId, long lockId)
      throws BlockDoesNotExistException, InvalidWorkerStateException {
    BlockMeta meta = mBlockStore.getBlockMeta(sessionId, blockId, lockId);
    return meta.getPath();
  }

  @Override
  public void unlockBlock(long lockId) throws BlockDoesNotExistException {
    mBlockStore.unlockBlock(lockId);
  }

  @Override
  public boolean unlockBlock(long sessionId, long blockId) {
    return mBlockStore.unlockBlock(sessionId, blockId);
  }

  @Override
  public long lockBlock(long sessionId, long blockId) throws BlockDoesNotExistException {
    return mBlockStore.lockBlock(sessionId, blockId);
  }

  @Override
  public long lockBlockNoException(long sessionId, long blockId) {
    return mBlockStore.lockBlockNoException(sessionId, blockId);
  }

  @Override
  public void createBlockRemote(long sessionId, long blockId, String tierAlias, long initialBytes)
      throws BlockAlreadyExistsException, WorkerOutOfSpaceException, IOException {
    BlockStoreLocation loc = BlockStoreLocation.anyDirInTier(tierAlias);
    CreateBlockOptions createBlockOptions = new CreateBlockOptions(
        null,
        loc.mediumType(),
        initialBytes);
    mBlockStore.createBlock(sessionId, blockId, loc.dir(), createBlockOptions);
  }

  @Override
  public BlockReader readUfsBlock(long sessionId, long blockId, long offset)
      throws BlockDoesNotExistException, IOException {
    return mBlockStore.createBlockReader(sessionId, blockId, offset);
  }

  @Override
  public BlockWriter getTempBlockWriterRemote(long sessionId, long blockId)
      throws BlockDoesNotExistException, BlockAlreadyExistsException, InvalidWorkerStateException,
      IOException {
    return mBlockStore.getBlockWriter(sessionId, blockId);
  }

  @Override
  public Set<Class<? extends Server>> getDependencies() {
    return new HashSet<>();
  }

  @Override
  public String getName() {
    return Constants.BLOCK_WORKER_NAME;
  }

  @Override
  public Map<ServiceType, GrpcService> getServices() {
    return Collections.emptyMap();
  }

  @Override
  public AtomicReference<Long> getWorkerId() {
    return mWorkerId;
  }

  /**
   * Runs the block worker. The thread must be called after all services (e.g., web, dataserver)
   * started.
   * <p>
   * BlockWorker doesn't support being restarted!
   */
  @Override
  public void start(WorkerNetAddress address) throws IOException {
    super.start(address);
    mAddress = address;

    // Acquire worker Id.
    askForWorkerId(address);

    Preconditions.checkNotNull(mWorkerId, "mWorkerId");
    Preconditions.checkNotNull(mAddress, "mAddress");

    // Setup BlockMasterSync
    BlockMasterSync blockMasterSync = mResourceCloser
        .register(new BlockMasterSync(this, mWorkerId, mAddress, mBlockMasterClientPool));
    getExecutorService()
        .submit(new HeartbeatThread(HeartbeatContext.WORKER_BLOCK_SYNC, blockMasterSync,
            (int) Configuration.getMs(PropertyKey.WORKER_BLOCK_HEARTBEAT_INTERVAL_MS),
            Configuration.global(), ServerUserState.global()));

    // Setup PinListSyncer
    PinListSync pinListSync = mResourceCloser.register(
        new PinListSync(this, mFileSystemMasterClient));
    getExecutorService()
        .submit(new HeartbeatThread(HeartbeatContext.WORKER_PIN_LIST_SYNC, pinListSync,
            (int) Configuration.getMs(PropertyKey.WORKER_BLOCK_HEARTBEAT_INTERVAL_MS),
            Configuration.global(), ServerUserState.global()));

    // Setup session cleaner
    SessionCleaner sessionCleaner = mResourceCloser
        .register(new SessionCleaner(mSessions, mBlockStore));
    getExecutorService().submit(sessionCleaner);

    // Setup storage checker
    if (Configuration.getBoolean(PropertyKey.WORKER_STORAGE_CHECKER_ENABLED)) {
      StorageChecker storageChecker = mResourceCloser.register(new StorageChecker());
      getExecutorService()
          .submit(new HeartbeatThread(HeartbeatContext.WORKER_STORAGE_HEALTH, storageChecker,
              (int) Configuration.getMs(PropertyKey.WORKER_BLOCK_HEARTBEAT_INTERVAL_MS),
              Configuration.global(), ServerUserState.global()));
    }
    // Mounts the embedded Fuse application
    if (Configuration.getBoolean(PropertyKey.WORKER_FUSE_ENABLED)) {
      mFuseManager.start();
    }
  }

  /**
   * Ask the master for a workerId. Should not be called outside of testing
   *
   * @param address the address this worker operates on
   */
  @VisibleForTesting
  public void askForWorkerId(WorkerNetAddress address) {
    BlockMasterClient blockMasterClient = mBlockMasterClientPool.acquire();
    try {
      RetryUtils.retry("create worker id", () -> mWorkerId.set(blockMasterClient.getId(address)),
          RetryUtils.defaultWorkerMasterClientRetry());
    } catch (Exception e) {
      throw new RuntimeException("Failed to create a worker id from block master: "
          + e.getMessage());
    } finally {
      mBlockMasterClientPool.release(blockMasterClient);
    }
  }

  /**
   * Stops the block worker. This method should only be called to terminate the worker.
   * <p>
   * BlockWorker doesn't support being restarted!
   */
  @Override
  public void stop() throws IOException {
    // Stop the base. (closes executors.)
    // This is intentionally called first in order to send interrupt signals to heartbeat threads.
    // Otherwise, if the heartbeat threads are not interrupted then the shutdown can hang.
    super.stop();
    // Stop heart-beat executors and clients.
    mResourceCloser.close();
  }

  @Override
  public void abortBlock(long sessionId, long blockId) throws IOException {
    mBlockStore.abortBlock(sessionId, blockId);
    Metrics.WORKER_ACTIVE_CLIENTS.dec();
  }

  @Override
  public void commitBlock(long sessionId, long blockId, boolean pinOnCreate) {
    mBlockStore.commitBlock(sessionId, blockId, pinOnCreate);
  }

  @Override
  public void commitBlockInUfs(long blockId, long length) {
    BlockMasterClient blockMasterClient = mBlockMasterClientPool.acquire();
    try {
      blockMasterClient.commitBlockInUfs(blockId, length);
    } catch (AlluxioStatusException e) {
      throw AlluxioRuntimeException.from(e);
    } finally {
      mBlockMasterClientPool.release(blockMasterClient);
    }
  }

  @Override
  public String createBlock(long sessionId, long blockId, int tier,
                            CreateBlockOptions createBlockOptions) {
    try {
      return mBlockStore.createBlock(sessionId, blockId, tier, createBlockOptions);
    } catch (ResourceExhaustedRuntimeException e) {
      // mAddress is null if the worker is not started
      if (mAddress == null) {
        throw new ResourceExhaustedRuntimeException(
            ExceptionMessage.CANNOT_REQUEST_SPACE.getMessage(mWorkerId.get(), blockId), e, false);
      }
      InetSocketAddress address =
          InetSocketAddress.createUnresolved(mAddress.getHost(), mAddress.getRpcPort());
      throw new ResourceExhaustedRuntimeException(
          ExceptionMessage.CANNOT_REQUEST_SPACE.getMessageWithUrl(
              RuntimeConstants.ALLUXIO_DEBUG_DOCS_URL, address, blockId), e, false);
    }
  }

  @Override
  public BlockWriter createBlockWriter(long sessionId, long blockId)
      throws IOException {
    return mBlockStore.createBlockWriter(sessionId, blockId);
  }

  @Override
  public BlockHeartbeatReport getReport() {
    return mHeartbeatReporter.generateReport();
  }

  @Override
  public BlockStoreMeta getStoreMeta() {
    return mBlockStore.getBlockStoreMeta();
  }

  @Override
  public BlockStoreMeta getStoreMetaFull() {
    return mBlockStore.getBlockStoreMetaFull();
  }

  @Override
  public List<String> getWhiteList() {
    return mWhitelist.getList();
  }

  @Override
  public BlockReader createUfsBlockReader(long sessionId, long blockId, long offset,
                                          boolean positionShort,
                                          Protocol.OpenUfsBlockOptions options)
      throws IOException {
    return mBlockStore.createUfsBlockReader(sessionId, blockId, offset, positionShort, options);
  }

  @Override
  public void removeBlock(long sessionId, long blockId)
      throws IOException {
    mBlockStore.removeBlock(sessionId, blockId);
  }

  @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
  @Override
  public void freeWorker() throws IOException {
    List<String> paths = new ArrayList<>();
    if (Configuration.global().get(PropertyKey.WORKER_BLOCK_STORE_TYPE) == BlockStoreType.FILE) {
      int tierCount = Configuration.global().getInt(PropertyKey.WORKER_TIERED_STORE_LEVELS);
      for (int i = 0; i < tierCount; i++) {
        paths.addAll(Configuration.global().getList(PropertyKey
            .Template.WORKER_TIERED_STORE_LEVEL_DIRS_PATH.format(i)));
      }
    } else if (Configuration.global()
        .get(PropertyKey.WORKER_BLOCK_STORE_TYPE) == BlockStoreType.PAGE) {
      paths.addAll(Configuration.global().getList(PropertyKey.WORKER_PAGE_STORE_DIRS));
    } else {
      throw new IllegalStateException("Unknown WORKER_BLOCK_STORE_TYPE.");
    }

    List<String> failDeleteDirs = new ArrayList<>();
    for (String tmpPath : paths) {
      File[] files = new File(tmpPath).listFiles();
      Preconditions.checkNotNull(files, "The path does not denote a directory.");
      for (File file : files) {
        try {
          FileUtils.deletePathRecursively(file.getPath());
        } catch (IOException ie) {
          failDeleteDirs.add(file.getPath());
        }
      }
    }
    if (!failDeleteDirs.isEmpty()) {
      LOG.info("Some directories fail to be deleted: " + failDeleteDirs);
      throw new IOException(failDeleteDirs.toString());
    }
    LOG.info("All blocks and directories in worker {} are freed.", getWorkerId());
  }

  @Override
  public void requestSpace(long sessionId, long blockId, long additionalBytes) {
    mBlockStore.requestSpace(sessionId, blockId, additionalBytes);
  }

  @Override
  @Deprecated
  public void asyncCache(AsyncCacheRequest request) {
    CacheRequest cacheRequest =
        CacheRequest.newBuilder().setBlockId(request.getBlockId()).setLength(request.getLength())
            .setOpenUfsBlockOptions(request.getOpenUfsBlockOptions())
            .setSourceHost(request.getSourceHost()).setSourcePort(request.getSourcePort())
            .setAsync(true).build();
    try {
      mCacheManager.submitRequest(cacheRequest);
    } catch (Exception e) {
      LOG.warn("Failed to submit async cache request. request: {}", request, e);
    }
  }

  @Override
  public void cache(CacheRequest request) throws AlluxioException, IOException {
    // todo(bowen): paged block store handles caching from UFS automatically and on-the-fly
    //  this will cause an unnecessary extra read of the block
    if (mBlockStore instanceof PagedBlockStore) {
      return;
    }
    mCacheManager.submitRequest(request);
  }

  @Override
  public CompletableFuture<List<BlockStatus>> load(List<Block> blocks, UfsReadOptions options) {
    return mBlockStore.load(blocks, options);
  }

  @Override
  public void updatePinList(Set<Long> pinnedInodes) {
    mBlockStore.updatePinnedInodes(pinnedInodes);
  }

  @Override
  public FileInfo getFileInfo(long fileId) throws IOException {
    return mFileSystemMasterClient.getFileInfo(fileId);
  }

  /**
   * Closes a UFS block for a client session. It also commits the block to Alluxio block store
   * if the UFS block has been cached successfully.
   *
   * @param sessionId the session ID
   * @param blockId   the block ID
   */

  @Override
  public BlockReader createBlockReader(long sessionId, long blockId, long offset,
                                       boolean positionShort, Protocol.OpenUfsBlockOptions options)
      throws IOException {
    BlockReader reader =
        mBlockStore.createBlockReader(sessionId, blockId, offset, positionShort, options);
    Metrics.WORKER_ACTIVE_CLIENTS.inc();
    return reader;
  }

  @Override
  public void clearMetrics() {
    // TODO(lu) Create a metrics worker and move this method to metrics worker
    MetricsSystem.resetAllMetrics();
  }

  @Override
  public alluxio.dora.wire.Configuration getConfiguration(GetConfigurationPOptions options) {
    // NOTE(cc): there is no guarantee that the returned cluster and path configurations are
    // consistent snapshot of the system's state at a certain time, the path configuration might
    // be in a newer state. But it's guaranteed that the hashes are respectively correspondent to
    // the properties.
    alluxio.dora.wire.Configuration.Builder builder = alluxio.dora.wire.Configuration.newBuilder();

    if (!options.getIgnoreClusterConf()) {
      for (PropertyKey key : Configuration.keySet()) {
        if (key.isBuiltIn()) {
          Source source = Configuration.getSource(key);
          Object value = Configuration.getOrDefault(key, null,
              ConfigurationValueOptions.defaults().useDisplayValue(true)
                  .useRawValue(options.getRawValue()));
          builder.addClusterProperty(key.getName(), value, source);
        }
      }
      // NOTE(cc): assumes that Configuration is read-only when master is running, otherwise,
      // the following hash might not correspond to the above cluster configuration.
      builder.setClusterConfHash(Configuration.hash());
    }

    return builder.build();
  }

  @Override
  public void cleanupSession(long sessionId) {
    mBlockStore.cleanupSession(sessionId);
    Metrics.WORKER_ACTIVE_CLIENTS.dec();
  }

  /**
   * This class contains some metrics related to the block worker.
   * This class is public because the metric names are referenced in
   * {@link WebInterfaceAbstractMetricsServlet}.
   */
  @ThreadSafe
  public static final class Metrics {
    public static final Counter WORKER_ACTIVE_CLIENTS =
        MetricsSystem.counter(MetricKey.WORKER_ACTIVE_CLIENTS.getName());

    /**
     * Registers metric gauges.
     *
     * @param blockWorker the block worker handle
     */
    public static void registerGauges(final BlockWorker blockWorker) {
      MetricsSystem.registerGaugeIfAbsent(
          MetricsSystem.getMetricName(MetricKey.WORKER_CAPACITY_TOTAL.getName()),
          () -> blockWorker.getStoreMeta().getCapacityBytes());

      MetricsSystem.registerGaugeIfAbsent(
          MetricsSystem.getMetricName(MetricKey.WORKER_CAPACITY_USED.getName()),
          () -> blockWorker.getStoreMeta().getUsedBytes());

      MetricsSystem.registerGaugeIfAbsent(
          MetricsSystem.getMetricName(MetricKey.WORKER_CAPACITY_FREE.getName()),
          () -> blockWorker.getStoreMeta().getCapacityBytes() - blockWorker.getStoreMeta()
              .getUsedBytes());

      for (int i = 0; i < WORKER_STORAGE_TIER_ASSOC.size(); i++) {
        String tier = WORKER_STORAGE_TIER_ASSOC.getAlias(i);
        // TODO(lu) Add template to dynamically generate MetricKey
        MetricsSystem.registerGaugeIfAbsent(MetricsSystem.getMetricName(
                MetricKey.WORKER_CAPACITY_TOTAL.getName() + MetricInfo.TIER + tier),
            () -> blockWorker.getStoreMeta().getCapacityBytesOnTiers().getOrDefault(tier, 0L));

        MetricsSystem.registerGaugeIfAbsent(MetricsSystem.getMetricName(
                MetricKey.WORKER_CAPACITY_USED.getName() + MetricInfo.TIER + tier),
            () -> blockWorker.getStoreMeta().getUsedBytesOnTiers().getOrDefault(tier, 0L));

        MetricsSystem.registerGaugeIfAbsent(MetricsSystem.getMetricName(
                MetricKey.WORKER_CAPACITY_FREE.getName() + MetricInfo.TIER + tier),
            () -> blockWorker.getStoreMeta().getCapacityBytesOnTiers().getOrDefault(tier, 0L)
                - blockWorker.getStoreMeta().getUsedBytesOnTiers().getOrDefault(tier, 0L));
      }
      MetricsSystem.registerGaugeIfAbsent(MetricsSystem.getMetricName(
              MetricKey.WORKER_BLOCKS_CACHED.getName()),
          () -> blockWorker.getStoreMetaFull().getNumberOfBlocks());
    }

    private Metrics() {
    } // prevent instantiation
  }

  /**
   * StorageChecker periodically checks the health of each storage path and report missing blocks to
   * {@link BlockWorker}.
   */
  @NotThreadSafe
  public final class StorageChecker implements HeartbeatExecutor {

    @Override
    public void heartbeat() {
      try {
        mBlockStore.removeInaccessibleStorage();
      } catch (Exception e) {
        LOG.warn("Failed to check storage: {}", e.toString());
        LOG.debug("Exception: ", e);
      }
    }

    @Override
    public void close() {
      // Nothing to clean up
    }
  }
}