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

package org.apache.geode.management.internal.cli.commands;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.security.SimpleTestSecurityManager;
import org.apache.geode.test.junit.rules.GfshShellConnectionRule;
import org.apache.geode.test.junit.rules.LocatorStarterRule;
import org.apache.geode.test.junit.categories.IntegrationTest;

@Category(IntegrationTest.class)
public class ConnectCommandWithSecurityTest {

  @ClassRule
  public static LocatorStarterRule locator =
      new LocatorStarterRule().withSecurityManager(SimpleTestSecurityManager.class).withAutoStart();

  @Rule
  public GfshShellConnectionRule gfsh = new GfshShellConnectionRule();

  @Test
  public void connectToLocator() throws Exception {
    gfsh.secureConnectAndVerify(locator.getPort(), GfshShellConnectionRule.PortType.locator,
        "clusterRead", "clusterRead");
    gfsh.executeAndVerifyCommand("list members");
  }

  @Test
  public void connectOverJmx() throws Exception {
    gfsh.secureConnectAndVerify(locator.getJmxPort(), GfshShellConnectionRule.PortType.jmxManager,
        "clusterRead", "clusterRead");
    gfsh.executeAndVerifyCommand("list members");
  }

  @Test
  public void connectOverHttp() throws Exception {
    gfsh.secureConnectAndVerify(locator.getHttpPort(), GfshShellConnectionRule.PortType.http,
        "clusterRead", "clusterRead");
    gfsh.executeAndVerifyCommand("list members");
  }
}
