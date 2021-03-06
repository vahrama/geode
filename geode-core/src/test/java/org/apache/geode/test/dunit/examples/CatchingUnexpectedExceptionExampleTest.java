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
package org.apache.geode.test.dunit.examples;

import static org.apache.geode.test.dunit.Assert.fail;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.Serializable;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.RegionFactory;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.test.dunit.Host;
import org.apache.geode.test.dunit.SerializableRunnable;
import org.apache.geode.test.dunit.rules.DistributedDisconnectRule;
import org.apache.geode.test.dunit.rules.DistributedTestRule;
import org.apache.geode.test.junit.categories.DistributedTest;

@Category(DistributedTest.class)
@SuppressWarnings("serial")
public class CatchingUnexpectedExceptionExampleTest implements Serializable {

  @ClassRule
  public static DistributedTestRule distributedTestRule = new DistributedTestRule();

  @Rule
  public DistributedDisconnectRule disconnectRule =
      new DistributedDisconnectRule.Builder().disconnectAfter(true).build();

  /**
   * Don't do this! Catch Exception and invoke fail => anti-pattern
   */
  @Test
  public void createRegion_withTryCatch_dontDoThis() throws Exception {
    Host.getHost(0).getVM(0).invoke(new SerializableRunnable("Create Region") {
      @Override
      public void run() {
        try {
          Cache cache = new CacheFactory().create();
          RegionFactory regionFactory = cache.createRegionFactory(new AttributesFactory().create());
          LocalRegion region = (LocalRegion) regionFactory.create("region1");
          assertThat(region).isNotNull();
        } catch (CacheException ex) {
          fail("While creating region", ex);
        }
      }
    });
  }

  /**
   * Use "throws Exception" is better!
   */
  @Test
  public void createRegion_withThrowsException_thisIsBetter() throws Exception {
    Host.getHost(0).getVM(0).invoke(new SerializableRunnable("Create Region") {
      @Override
      public void run() throws Exception {
        Cache cache = new CacheFactory().create();
        RegionFactory regionFactory = cache.createRegionFactory(new AttributesFactory().create());
        LocalRegion region = (LocalRegion) regionFactory.create("region1");
        assertThat(region).isNotNull();
      }
    });
  }

  /**
   * Use lambda without having to specify run() with throws Exception -- best!
   */
  @Test
  public void createRegion_withLambda_thisIsBest() throws Exception {
    Host.getHost(0).getVM(0).invoke("Create Region", () -> {
      Cache cache = new CacheFactory().create();
      RegionFactory regionFactory = cache.createRegionFactory(new AttributesFactory().create());
      LocalRegion region = (LocalRegion) regionFactory.create("region1");
      assertThat(region).isNotNull();
    });
  }
}
