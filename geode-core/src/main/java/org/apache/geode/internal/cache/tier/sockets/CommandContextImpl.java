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

import org.apache.geode.internal.cache.tier.CommandContext;
import org.apache.geode.internal.security.SecurityService;

public class CommandContextImpl implements CommandContext {

  private final Message clientMessage;
  private final ServerConnection serverConnection;
  private final SecurityService securityService;
  private final long statTimeStart;

  public CommandContextImpl(Message clientMessage, ServerConnection serverConnection,
      SecurityService securityService) {
    this(clientMessage, serverConnection, securityService, 0);
  }

  public CommandContextImpl(CommandContext commandContext, long statTimeStart) {
    this(commandContext.getMessage(), commandContext.getServerConnection(),
        commandContext.getSecurityService(), statTimeStart);
  }

  public CommandContextImpl(Message clientMessage, ServerConnection serverConnection,
      SecurityService securityService, long statTimeStart) {
    this.clientMessage = clientMessage;
    this.serverConnection = serverConnection;
    this.securityService = securityService;
    this.statTimeStart = statTimeStart;
  }

  @Override
  public Message getMessage() {
    return this.clientMessage;
  }

  @Override
  public ServerConnection getServerConnection() {
    return this.serverConnection;
  }

  @Override
  public SecurityService getSecurityService() {
    return this.securityService;
  }

  @Override
  public long getStatTimeStart() {
    return this.statTimeStart;
  }
}
