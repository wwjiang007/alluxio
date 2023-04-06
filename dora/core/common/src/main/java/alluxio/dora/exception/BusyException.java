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

package alluxio.dora.exception;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Thrown when the system is busy.
 */
@ThreadSafe
public class BusyException extends AlluxioException {
  public static final String CUSTOM_EXCEPTION_MESSAGE = "BusyException";
  private static final long serialVersionUID = -5289662357677980794L;

  /**
   * Constructs a new busy exception.
   */
  public BusyException() {
    super(CUSTOM_EXCEPTION_MESSAGE);
  }
}
