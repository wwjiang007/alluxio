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

package alluxio.dora.worker;

import alluxio.dora.ProcessUtils;
import alluxio.dora.RuntimeConstants;
import alluxio.dora.conf.Configuration;
import alluxio.dora.grpc.Scope;
import alluxio.dora.master.MasterInquireClient;
import alluxio.dora.retry.RetryUtils;
import alluxio.dora.user.ServerUserState;
import alluxio.dora.util.CommonUtils;
import alluxio.dora.util.ConfigurationUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Entry point for the Alluxio worker.
 */
@ThreadSafe
public final class AlluxioJobWorker {
  private static final Logger LOG = LoggerFactory.getLogger(AlluxioJobWorker.class);

  /**
   * Starts the Alluxio job worker.
   *
   * @param args command line arguments, should be empty
   */
  public static void main(String[] args) {
    if (args.length != 0) {
      LOG.info("java -cp {} {}", RuntimeConstants.ALLUXIO_JAR,
          AlluxioJobWorker.class.getCanonicalName());
      System.exit(-1);
    }

    if (!ConfigurationUtils.masterHostConfigured(Configuration.global())) {
      System.out.println(ConfigurationUtils
          .getMasterHostNotConfiguredMessage("Alluxio job worker"));
      System.exit(1);
    }

    if (!ConfigurationUtils.jobMasterHostConfigured(Configuration.global())) {
      System.out.println(ConfigurationUtils
          .getJobMasterHostNotConfiguredMessage("Alluxio job worker"));
      System.exit(1);
    }

    CommonUtils.PROCESS_TYPE.set(CommonUtils.ProcessType.JOB_WORKER);
    MasterInquireClient masterInquireClient =
        MasterInquireClient.Factory.create(Configuration.global(), ServerUserState.global());
    try {
      RetryUtils.retry("load cluster default configuration with master", () -> {
        InetSocketAddress masterAddress = masterInquireClient.getPrimaryRpcAddress();
        Configuration.loadClusterDefaults(masterAddress, Scope.WORKER);
      }, RetryUtils.defaultWorkerMasterClientRetry());
    } catch (IOException e) {
      ProcessUtils.fatalError(LOG,
          "Failed to load cluster default configuration for job worker. Please make sure that "
              + "Alluxio master is running: %s", e.toString());
    }
    JobWorkerProcess process;
    try {
      process = JobWorkerProcess.Factory.create();
    } catch (Throwable t) {
      ProcessUtils.fatalError(LOG, t, "Failed to create job worker process");
      // fatalError will exit, so we shouldn't reach here.
      throw t;
    }

    ProcessUtils.stopProcessOnShutdown(process);
    ProcessUtils.run(process);
  }

  private AlluxioJobWorker() {} // prevent instantiation
}