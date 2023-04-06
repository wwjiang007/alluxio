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

package alluxio.dora.client.fs.io;

import alluxio.dora.AlluxioURI;
import alluxio.dora.client.file.FileInStream;
import alluxio.dora.client.file.FileOutStream;
import alluxio.dora.client.file.FileSystem;
import alluxio.dora.client.file.URIStatus;
import alluxio.dora.conf.PropertyKey;
import alluxio.dora.grpc.CreateFilePOptions;
import alluxio.dora.grpc.WritePType;
import alluxio.dora.master.MasterProcess;
import alluxio.dora.master.block.BlockMaster;
import alluxio.dora.testutils.LocalAlluxioClusterResource;
import alluxio.dora.util.io.PathUtils;

import org.junit.Assert;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

import java.io.IOException;

/**
 * Integration tests for {@link FileInStream} for testing
 * re-hydration of block metadata by the master.
 */
public class FileInStreamRehydrationIntegrationTest extends AbstractFileOutStreamIntegrationTest {
  protected static final int WORKER_MEMORY_SIZE = 500;
  protected static final int BLOCK_BYTES = 200;

  @Override
  protected void customizeClusterResource(LocalAlluxioClusterResource.Builder resource) {
    resource.setProperty(PropertyKey.WORKER_RAMDISK_SIZE, WORKER_MEMORY_SIZE)
        .setProperty(PropertyKey.USER_BLOCK_SIZE_BYTES_DEFAULT, BLOCK_BYTES)
        .setProperty(PropertyKey.USER_FILE_UFS_TIER_ENABLED, true)
        .setProperty(PropertyKey.WORKER_NETWORK_NETTY_WATERMARK_HIGH, "1.0");
  }

  @Test
  public void testRehydration() throws Exception {
    FileSystem fs = FileSystem.Factory.create();

    // Create a file with 10 blocks.
    AlluxioURI filePath = new AlluxioURI(PathUtils.uniqPath());
    CreateFilePOptions op = CreateFilePOptions.newBuilder().setWriteType(WritePType.CACHE_THROUGH)
        .setRecursive(true).build();
    byte[] content = generateContent(BLOCK_BYTES * 10);
    try (FileOutStream os = fs.createFile(filePath, op)) {
      os.write(content);
    }

    // Validate file size and content.
    validateAndCloseFileStream(mFileSystem.openFile(filePath), content);

    // Grab the block master for emulating lost blocks.
    MasterProcess masterProcess = Whitebox.getInternalState(
            mLocalAlluxioClusterResource.get().getLocalAlluxioMaster(), "mMasterProcess");
    BlockMaster blockMaster = masterProcess.getMaster(BlockMaster.class);

    // Remove blocks externally from block-master.
    URIStatus status = fs.getStatus(filePath);
    blockMaster.removeBlocks(status.getBlockIds(), true);

    // Validate file size and content.
    validateAndCloseFileStream(mFileSystem.openFile(filePath), content);
  }

  private byte[] generateContent(int size) {
    byte[] content = new byte[size];
    for (int i = 0; i < size; i++) {
      content[i] = (byte) i;
    }
    return content;
  }

  private void validateAndCloseFileStream(FileInStream inStream, byte[] content)
      throws IOException {
    try {
      int contentIdx = 0;
      Assert.assertEquals(content.length, inStream.remaining());
      while (inStream.remaining() > 0) {
        Assert.assertTrue(contentIdx < content.length);
        Assert.assertEquals(content[contentIdx++], (byte) inStream.read());
      }
    } finally {
      inStream.close();
    }
  }
}