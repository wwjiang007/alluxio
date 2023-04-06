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

package alluxio.dora.server.ft.journal;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import alluxio.dora.master.AlluxioMasterProcess;
import alluxio.dora.master.LocalAlluxioCluster;
import alluxio.dora.master.meta.DefaultMetaMaster;
import alluxio.dora.master.meta.MetaMaster;
import alluxio.dora.testutils.LocalAlluxioClusterResource;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Integration tests for meta master journaling.
 */
public class MetaMasterJournalTest {
  @Rule
  public LocalAlluxioClusterResource mClusterResource =
      new LocalAlluxioClusterResource.Builder()
          .build();
  private LocalAlluxioCluster mCluster;

  @Before
  public void before() {
    mCluster = mClusterResource.get();
  }

  @Test
  public void journalClusterID() throws Exception {
    MetaMaster metaMaster =
        mCluster.getLocalAlluxioMaster().getMasterProcess().getMaster(MetaMaster.class);
    String clusterID = metaMaster.getClusterID();
    assertNotNull(clusterID);
    assertNotEquals(clusterID, DefaultMetaMaster.INVALID_CLUSTER_ID);
    mCluster.stopMasters();
    mCluster.startMasters();
    AlluxioMasterProcess masterProcess = mCluster.getLocalAlluxioMaster().getMasterProcess();
    assertTrue(masterProcess.getMaster(MetaMaster.class).getClusterID().equals(clusterID));
  }
}