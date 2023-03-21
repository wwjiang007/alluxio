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

package alluxio.master.journal.raft;

import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.grpc.LatestSnapshotInfoPRequest;
import alluxio.grpc.RaftJournalServiceGrpc;
import alluxio.grpc.SnapshotData;
import alluxio.grpc.SnapshotMetadata;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;
import alluxio.util.TarUtils;

import com.codahale.metrics.Timer;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnsafeByteOperations;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.statemachine.SnapshotInfo;
import org.apache.ratis.statemachine.StateMachineStorage;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * RPC handler for raft journal service.
 */
public class RaftJournalServiceHandler extends RaftJournalServiceGrpc.RaftJournalServiceImplBase {
  private static final Logger LOG =
      LoggerFactory.getLogger(RaftJournalServiceHandler.class);
  private final int mSnapshotReplicationChunkSize = (int) Configuration.getBytes(
      PropertyKey.MASTER_EMBEDDED_JOURNAL_SNAPSHOT_REPLICATION_CHUNK_SIZE);
  private final int mSnapshotCompressionLevel =
      Configuration.getInt(PropertyKey.MASTER_METASTORE_ROCKS_CHECKPOINT_COMPRESSION_LEVEL);

  private final StateMachineStorage mStateMachineStorage;
  private long mLastSnapshotUploadDuration = -1;
  private long mLastSnapshotUploadSize = -1;
  private long mLastSnapshotUploadDiskSize = -1;

  /**
   * @param storage the storage that the state machine uses for its snapshots
   */
  public RaftJournalServiceHandler(StateMachineStorage storage) {
    mStateMachineStorage = storage;

    MetricsSystem.registerGaugeIfAbsent(
        MetricKey.MASTER_EMBEDDED_JOURNAL_LAST_SNAPSHOT_UPLOAD_DURATION.getName(),
        () -> mLastSnapshotUploadDuration);
    MetricsSystem.registerGaugeIfAbsent(
        MetricKey.MASTER_EMBEDDED_JOURNAL_LAST_SNAPSHOT_UPLOAD_SIZE.getName(),
        () -> mLastSnapshotUploadSize);
    MetricsSystem.registerGaugeIfAbsent(
        MetricKey.MASTER_EMBEDDED_JOURNAL_LAST_SNAPSHOT_UPLOAD_DISK_SIZE.getName(),
        () -> mLastSnapshotUploadDiskSize);
  }

  @Override
  public void requestLatestSnapshotInfo(LatestSnapshotInfoPRequest request,
                                        StreamObserver<SnapshotMetadata> responseObserver) {
    LOG.info("Received request for latest snapshot info");
    if (Context.current().isCancelled()) {
      responseObserver.onError(
          Status.CANCELLED.withDescription("Cancelled by client").asRuntimeException());
      return;
    }
    SnapshotInfo snapshot = mStateMachineStorage.getLatestSnapshot();
    SnapshotMetadata.Builder metadata = SnapshotMetadata.newBuilder();
    if (snapshot == null) {
      LOG.info("No snapshot to send");
      metadata.setExists(false);
    } else {
      LOG.info("Found snapshot {}", snapshot.getTermIndex());
      metadata.setExists(true)
          .setSnapshotTerm(snapshot.getTerm())
          .setSnapshotIndex(snapshot.getIndex());
    }
    responseObserver.onNext(metadata.build());
    responseObserver.onCompleted();
  }

  @Override
  public void requestLatestSnapshotData(SnapshotMetadata request,
                                     StreamObserver<SnapshotData> responseObserver) {
    TermIndex index = TermIndex.valueOf(request.getSnapshotTerm(), request.getSnapshotIndex());
    LOG.info("Received request for snapshot data {}", index);
    if (Context.current().isCancelled()) {
      responseObserver.onError(
          Status.CANCELLED.withDescription("Cancelled by client").asRuntimeException());
      return;
    }
    Timer.Context time = MetricsSystem.timer(
        MetricKey.MASTER_EMBEDDED_JOURNAL_SNAPSHOT_UPLOAD_TIMER.getName()).time();

    String snapshotDirName = SimpleStateMachineStorage
        .getSnapshotFileName(request.getSnapshotTerm(), request.getSnapshotIndex());
    Path snapshotPath = new File(mStateMachineStorage.getSnapshotDir(), snapshotDirName).toPath();

    try (OutputStream snapshotOutStream = new OutputStream() {
      long mTotalBytesSent = 0L;
      byte[] mBuffer = new byte[mSnapshotReplicationChunkSize];
      int mBufferPosition = 0;

      @Override
      public void write(int b) {
        mBuffer[mBufferPosition++] = (byte) b;
        if (mBufferPosition == mBuffer.length) {
          flushBuffer();
        }
      }

      @Override
      public void close() {
        if (mBufferPosition > 0) {
          flushBuffer();
        }
        mLastSnapshotUploadDuration = time.stop() / 1_000_000L; // to get a value in ms
        LOG.debug("Total bytes sent: {}", mTotalBytesSent);
        MetricsSystem.histogram(
                MetricKey.MASTER_EMBEDDED_JOURNAL_SNAPSHOT_UPLOAD_HISTOGRAM.getName())
            .update(mTotalBytesSent);
        mLastSnapshotUploadSize = mTotalBytesSent;
        LOG.info("Uploaded snapshot {}", index);
      }

      private void flushBuffer() {
        // avoids copy
        ByteString bytes = UnsafeByteOperations.unsafeWrap(mBuffer, 0, mBufferPosition);
        mBuffer = new byte[mSnapshotReplicationChunkSize];
        LOG.trace("Sending chunk of size {}: {}", mBufferPosition, bytes.toByteArray());
        responseObserver.onNext(SnapshotData.newBuilder().setChunk(bytes).build());
        mTotalBytesSent += mBufferPosition;
        mBufferPosition = 0;
      }
    }) {
      LOG.debug("Begin snapshot upload of {}", index);
      mLastSnapshotUploadDiskSize = TarUtils.writeTarGz(snapshotPath, snapshotOutStream,
          mSnapshotCompressionLevel);
      MetricsSystem.histogram(
          MetricKey.MASTER_EMBEDDED_JOURNAL_SNAPSHOT_UPLOAD_DISK_HISTOGRAM.getName())
          .update(mLastSnapshotUploadDiskSize);
      LOG.debug("Total snapshot uncompressed bytes: {}", mLastSnapshotUploadDiskSize);
    } catch (Exception e) {
      LOG.info("Failed to upload snapshot {}", index);
      responseObserver.onError(e);
    } finally {
      responseObserver.onCompleted();
    }
  }
}
