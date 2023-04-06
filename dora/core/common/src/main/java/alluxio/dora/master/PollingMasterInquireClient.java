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

package alluxio.dora.master;

import static java.util.stream.Collectors.joining;

import alluxio.dora.conf.AlluxioConfiguration;
import alluxio.dora.conf.PropertyKey;
import alluxio.dora.exception.status.AlluxioStatusException;
import alluxio.dora.exception.status.CancelledException;
import alluxio.dora.exception.status.DeadlineExceededException;
import alluxio.dora.exception.status.UnavailableException;
import alluxio.dora.grpc.GetServiceVersionPRequest;
import alluxio.dora.grpc.GrpcChannel;
import alluxio.dora.grpc.GrpcChannelBuilder;
import alluxio.dora.grpc.GrpcServerAddress;
import alluxio.dora.grpc.ServiceType;
import alluxio.dora.grpc.ServiceVersionClientServiceGrpc;
import alluxio.dora.retry.RetryPolicy;
import alluxio.dora.retry.RetryUtils;
import alluxio.dora.security.user.UserState;
import alluxio.dora.uri.Authority;
import alluxio.dora.uri.MultiMasterAuthority;

import com.google.common.collect.Lists;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * PollingMasterInquireClient finds the address of the primary master by polling a list of master
 * addresses to see if their RPC servers are serving. This works because only primary masters serve
 * RPCs.
 */
public class PollingMasterInquireClient implements MasterInquireClient {
  private static final Logger LOG = LoggerFactory.getLogger(PollingMasterInquireClient.class);

  private final MultiMasterConnectDetails mConnectDetails;
  private final Supplier<RetryPolicy> mRetryPolicySupplier;
  private final AlluxioConfiguration mConfiguration;
  private final UserState mUserState;
  private final ServiceType mServiceType;

  /**
   * @param masterAddresses the potential master addresses
   * @param alluxioConf Alluxio configuration
   * @param userState user state
   * @param serviceType service type
   */
  public PollingMasterInquireClient(List<InetSocketAddress> masterAddresses,
      AlluxioConfiguration alluxioConf,
      UserState userState, ServiceType serviceType) {
    this(masterAddresses, RetryUtils::defaultClientRetry,
        alluxioConf, userState, serviceType);
  }

  /**
   * @param masterAddresses the potential master addresses
   * @param retryPolicySupplier the retry policy supplier
   * @param alluxioConf Alluxio configuration
   * @param serviceType service type
   */
  public PollingMasterInquireClient(List<InetSocketAddress> masterAddresses,
      Supplier<RetryPolicy> retryPolicySupplier,
      AlluxioConfiguration alluxioConf, ServiceType serviceType) {
    this(masterAddresses, retryPolicySupplier, alluxioConf,
        UserState.Factory.create(alluxioConf), serviceType);
  }

  /**
   * @param masterAddresses the potential master addresses
   * @param retryPolicySupplier the retry policy supplier
   * @param alluxioConf Alluxio configuration
   * @param userState user state
   * @param serviceType service type
   */
  public PollingMasterInquireClient(List<InetSocketAddress> masterAddresses,
      Supplier<RetryPolicy> retryPolicySupplier,
      AlluxioConfiguration alluxioConf,
      UserState userState, ServiceType serviceType) {
    mConnectDetails = new MultiMasterConnectDetails(masterAddresses);
    mRetryPolicySupplier = retryPolicySupplier;
    mConfiguration = alluxioConf;
    mUserState = userState;
    mServiceType = serviceType;
  }

  @Override
  public InetSocketAddress getPrimaryRpcAddress() throws UnavailableException {
    RetryPolicy retry = mRetryPolicySupplier.get();
    while (retry.attempt()) {
      InetSocketAddress address = getAddress();
      if (address != null) {
        return address;
      }
    }
    throw new UnavailableException(String.format(
        "Failed to determine primary master rpc address after polling each of %s %d times",
        mConnectDetails.getAddresses(), retry.getAttemptCount()));
  }

  @Nullable
  private InetSocketAddress getAddress() {
    // Iterate over the masters and try to connect to each of their RPC ports.
    List<InetSocketAddress> addresses;
    if (mConfiguration.getBoolean(PropertyKey.USER_RPC_SHUFFLE_MASTERS_ENABLED)) {
      addresses =
          Lists.newArrayList(mConnectDetails.getAddresses());
      Collections.shuffle(addresses);
    } else {
      addresses = mConnectDetails.getAddresses();
    }

    for (InetSocketAddress address : addresses) {
      try {
        LOG.debug("Checking whether {} is listening for RPCs", address);
        pingMetaService(address);
        LOG.debug("Successfully connected to {}", address);
        return address;
      } catch (UnavailableException e) {
        LOG.debug("Failed to connect to {}", address);
      } catch (DeadlineExceededException e) {
        LOG.debug("Timeout while connecting to {}", address);
      } catch (CancelledException e) {
        LOG.debug("Cancelled while connecting to {}", address);
      } catch (AlluxioStatusException e) {
        LOG.error("Error while connecting to {}. {}", address, e);
        // Breaking the loop on non filtered error.
        break;
      }
    }
    return null;
  }

  private void pingMetaService(InetSocketAddress address) throws AlluxioStatusException {
    // disable authentication in the channel since version service does not require authentication
    GrpcChannel channel =
        GrpcChannelBuilder.newBuilder(GrpcServerAddress.create(address), mConfiguration)
            .setSubject(mUserState.getSubject())
            .disableAuthentication().build();
    ServiceVersionClientServiceGrpc.ServiceVersionClientServiceBlockingStub versionClient =
        ServiceVersionClientServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(mConfiguration.getMs(PropertyKey.USER_MASTER_POLLING_TIMEOUT),
                TimeUnit.MILLISECONDS);
    try {
      versionClient.getServiceVersion(GetServiceVersionPRequest.newBuilder()
          .setServiceType(mServiceType).build());
    } catch (StatusRuntimeException e) {
      throw AlluxioStatusException.fromThrowable(e);
    } finally {
      channel.shutdown();
    }
  }

  @Override
  public List<InetSocketAddress> getMasterRpcAddresses() {
    return mConnectDetails.getAddresses();
  }

  @Override
  public ConnectDetails getConnectDetails() {
    return mConnectDetails;
  }

  /**
   * Details used to connect to the leader Alluxio master when there are multiple potential leaders.
   */
  public static class MultiMasterConnectDetails implements ConnectDetails {
    private final List<InetSocketAddress> mAddresses;

    /**
     * @param addresses a list of addresses
     */
    public MultiMasterConnectDetails(List<InetSocketAddress> addresses) {
      mAddresses = addresses;
    }

    /**
     * @return the addresses
     */
    public List<InetSocketAddress> getAddresses() {
      return mAddresses;
    }

    @Override
    public Authority toAuthority() {
      return new MultiMasterAuthority(mAddresses.stream()
          .map(addr -> addr.getHostString() + ":" + addr.getPort()).collect(joining(",")));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof MultiMasterConnectDetails)) {
        return false;
      }
      MultiMasterConnectDetails that = (MultiMasterConnectDetails) o;
      return mAddresses.equals(that.mAddresses);
    }

    @Override
    public int hashCode() {
      return Objects.hash(mAddresses);
    }

    @Override
    public String toString() {
      return toAuthority().toString();
    }
  }
}