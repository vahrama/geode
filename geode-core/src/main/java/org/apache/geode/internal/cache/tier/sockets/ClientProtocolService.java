/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.internal.cache.tier.sockets;

import org.apache.geode.StatisticsFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.distributed.internal.InternalLocator;
import org.apache.geode.internal.security.SecurityService;
import org.apache.geode.security.internal.server.Authenticator;

/**
 * Provides a convenient location for a client protocol service to be loaded into the system.
 */
public interface ClientProtocolService {
  void initializeStatistics(String statisticsName, StatisticsFactory factory);

  /**
   *
   * The pipeline MUST use an available authenticator for authentication of all operations once the
   * handshake has happened.
   *
   * @param availableAuthenticators A list of valid authenticators for the current system.
   */
  ClientProtocolProcessor createProcessorForCache(Cache cache,
      Authenticator availableAuthenticators, SecurityService securityService);

  /**
   * Create a locator pipeline. The locator does not currently provide any authentication.
   */
  ClientProtocolProcessor createProcessorForLocator(InternalLocator locator);
}
