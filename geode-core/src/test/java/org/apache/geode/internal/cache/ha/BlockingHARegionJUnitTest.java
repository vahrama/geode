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
package org.apache.geode.internal.cache.ha;

import static java.util.concurrent.TimeUnit.*;
import static org.apache.geode.distributed.ConfigurationProperties.*;
import static org.apache.geode.internal.cache.ha.HARegionQueue.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.CacheFactory;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.internal.cache.EventID;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.test.dunit.ThreadUtils;
import org.apache.geode.test.junit.categories.ClientSubscriptionTest;
import org.apache.geode.test.junit.categories.IntegrationTest;
import org.apache.geode.test.junit.rules.serializable.SerializableErrorCollector;

/**
 * Integration tests for Blocking HARegionQueue.
 *
 * <p>
 * #40314: Filled up queue causes all publishers to block
 *
 * <p>
 * #37627: In case of out of order messages, (sequence Id violation), in spite of HARQ not full, the
 * capacity (putPermits) of the HARQ exhausted.
 */
@Category({IntegrationTest.class, ClientSubscriptionTest.class})
public class BlockingHARegionJUnitTest {

  public static final String REGION = "BlockingHARegionJUnitTest_Region";
  private static final long THREAD_TIMEOUT = 2 * 60 * 1000;

  private final Object numberForThreadsLock = new Object();
  private int numberForDoPuts;
  private int numberForDoTakes;

  volatile boolean stopThreads;

  private InternalCache cache;
  private HARegionQueueAttributes queueAttributes;
  private List<Thread> threads;
  private ThreadGroup threadGroup;

  @Rule
  public SerializableErrorCollector errorCollector = new SerializableErrorCollector();

  @Before
  public void setUp() throws Exception {
    synchronized (this.numberForThreadsLock) {
      this.numberForDoPuts = 0;
      this.numberForDoTakes = 0;
    }

    this.stopThreads = false;
    this.threads = new ArrayList<>();
    this.threadGroup = new ThreadGroup(getClass().getSimpleName()) {
      @Override
      public void uncaughtException(Thread t, Throwable e) {
        errorCollector.addError(e);
      }
    };

    this.queueAttributes = new HARegionQueueAttributes();

    Properties config = new Properties();
    config.setProperty(MCAST_PORT, "0");

    this.cache = (InternalCache) CacheFactory.create(DistributedSystem.connect(config));
  }

  @After
  public void tearDown() throws Exception {
    try {
      this.stopThreads = true;
      for (Thread thread : this.threads) {
        thread.interrupt();
        ThreadUtils.join(thread, THREAD_TIMEOUT);
      }
    } finally {
      if (this.cache != null) {
        this.cache.close();
      }
    }
  }

  /**
   * This test has a scenario where the HARegionQueue capacity is just 1. There will be two thread.
   * One doing a 1000 puts and the other doing a 1000 takes. The validation for this test is that it
   * should not encounter any exceptions
   */
  @Test
  public void testBoundedPuts() throws Exception {
    this.queueAttributes.setBlockingQueueCapacity(1);
    HARegionQueue hrq = getHARegionQueueInstance(REGION, this.cache, this.queueAttributes,
        BLOCKING_HA_QUEUE, false);
    hrq.setPrimary(true); // fix for 40314 - capacity constraint is checked for primary only

    startDoPuts(hrq, 1000);
    startDoTakes(hrq, 1000);
  }

  /**
   * This test tests whether puts are blocked. There are two threads. One which is going to do 2
   * puts and one which is going to do take a single take. The capacity of the region is just 1. The
   * put thread is first started and it is then ensured that only one put has successfully made
   * through and that the thread is still alive. Then the take thread is started. This will cause
   * the region size to come down by one and the put thread waiting will go ahead and do the put.
   * The thread should then die and the region size should be validated to reflect that.
   */
  @Test
  public void testPutBeingBlocked() throws Exception {
    this.queueAttributes.setBlockingQueueCapacity(1);
    HARegionQueue hrq = getHARegionQueueInstance(REGION, this.cache, this.queueAttributes,
        BLOCKING_HA_QUEUE, false);
    hrq.setPrimary(true); // fix for 40314 - capacity constraint is checked for primary only

    Thread doPuts = startDoPuts(hrq, 2);

    await().until(() -> assertTrue(hrq.region.size() == 2));

    // thread should still be alive (in wait state)
    assertTrue(doPuts.isAlive());

    startDoTakes(hrq, 1);

    await().until(() -> assertTrue(hrq.region.size() == 3));
  }

  /**
   * This test tests that the region capacity is never exceeded even in highly concurrent
   * environments. The region capacity is set to 10000. Then 5 threads start doing put
   * simultaneously. They will reach a state where the queue is full and they will all go in a wait
   * state. the region size would be verified to be 20000 (10000 puts and 10000 DACE objects). then
   * the threads are interrupted and made to quit the loop
   */
  @Test
  public void testConcurrentPutsNotExceedingLimit() throws Exception {
    this.queueAttributes.setBlockingQueueCapacity(10000);
    HARegionQueue hrq = getHARegionQueueInstance(REGION, this.cache, this.queueAttributes,
        BLOCKING_HA_QUEUE, false);
    hrq.setPrimary(true); // fix for 40314 - capacity constraint is checked for primary only

    Thread doPuts1 = startDoPuts(hrq, 20000, 1);
    Thread doPuts2 = startDoPuts(hrq, 20000, 2);
    Thread doPuts3 = startDoPuts(hrq, 20000, 3);
    Thread doPuts4 = startDoPuts(hrq, 20000, 4);
    Thread doPuts5 = startDoPuts(hrq, 20000, 5);

    await().until(() -> assertTrue(hrq.region.size() == 20000));

    assertTrue(doPuts1.isAlive());
    assertTrue(doPuts2.isAlive());
    assertTrue(doPuts3.isAlive());
    assertTrue(doPuts4.isAlive());
    assertTrue(doPuts5.isAlive());

    assertTrue(hrq.region.size() == 20000);
  }

  /**
   * This test tests that the region capacity is never exceeded even in highly concurrent
   * environments. The region capacity is set to 10000. Then 5 threads start doing put
   * simultaneously. They will reach a state where the queue is full and they will all go in a wait
   * state. the region size would be verified to be 20000 (10000 puts and 10000 DACE objects). then
   * the threads are interrupted and made to quit the loop
   */
  @Ignore("Test is disabled until/if blocking queue capacity becomes a hard limit")
  @Test
  public void testConcurrentPutsTakesNotExceedingLimit() throws Exception {
    this.queueAttributes.setBlockingQueueCapacity(10000);
    HARegionQueue hrq = getHARegionQueueInstance(REGION, this.cache, this.queueAttributes,
        BLOCKING_HA_QUEUE, false);
    hrq.setPrimary(true); // fix for 40314 - capacity constraint is checked for primary only

    Thread doPuts1 = startDoPuts(hrq, 40000, 1);
    Thread doPuts2 = startDoPuts(hrq, 40000, 2);
    Thread doPuts3 = startDoPuts(hrq, 40000, 3);
    Thread doPuts4 = startDoPuts(hrq, 40000, 4);
    Thread doPuts5 = startDoPuts(hrq, 40000, 5);

    Thread doTakes1 = startDoTakes(hrq, 5000);
    Thread doTakes2 = startDoTakes(hrq, 5000);
    Thread doTakes3 = startDoTakes(hrq, 5000);
    Thread doTakes4 = startDoTakes(hrq, 5000);
    Thread doTakes5 = startDoTakes(hrq, 5000);

    ThreadUtils.join(doTakes1, 30 * 1000);
    ThreadUtils.join(doTakes2, 30 * 1000);
    ThreadUtils.join(doTakes3, 30 * 1000);
    ThreadUtils.join(doTakes4, 30 * 1000);
    ThreadUtils.join(doTakes5, 30 * 1000);

    await().until(() -> assertTrue(hrq.region.size() == 20000));

    assertTrue(doPuts1.isAlive());
    assertTrue(doPuts2.isAlive());
    assertTrue(doPuts3.isAlive());
    assertTrue(doPuts4.isAlive());
    assertTrue(doPuts5.isAlive());

    assertTrue(hrq.region.size() == 20000);
  }

  /**
   * Tests the bug in HARegionQueue where the take side put permit is not being incremented when the
   * event arriving at the queue which has optimistically decreased the put permit, is not
   * incrementing the take permit if the event has a sequence ID less than the last dispatched
   * sequence ID. This event is rightly rejected from entering the queue but the take permit also
   * needs to increase & a notify issued
   */
  @Test
  public void testHARQMaxCapacity_Bug37627() throws Exception {
    this.queueAttributes.setBlockingQueueCapacity(1);
    this.queueAttributes.setExpiryTime(180);
    HARegionQueue hrq = getHARegionQueueInstance(REGION, this.cache, this.queueAttributes,
        BLOCKING_HA_QUEUE, false);
    hrq.setPrimary(true); // fix for 40314 - capacity constraint is checked for primary only

    EventID event1 = new EventID(new byte[] {1}, 1, 2); // violation
    EventID event2 = new EventID(new byte[] {1}, 1, 1); // ignored
    EventID event3 = new EventID(new byte[] {1}, 1, 3);

    newThread(new Runnable() {
      @Override
      public void run() {
        try {
          hrq.put(new ConflatableObject("key1", "value1", event1, false, "region1"));
          hrq.take();
          hrq.put(new ConflatableObject("key2", "value1", event2, false, "region1"));
          hrq.put(new ConflatableObject("key3", "value1", event3, false, "region1"));
        } catch (Exception e) {
          errorCollector.addError(e);
        }
      }
    });
  }

  private Thread newThread(Runnable runnable) {
    Thread thread = new Thread(this.threadGroup, runnable);
    this.threads.add(thread);
    thread.start();
    return thread;
  }

  private Thread startDoPuts(HARegionQueue haRegionQueue, int count) {
    return startDoPuts(haRegionQueue, count, 0);
  }

  private Thread startDoPuts(HARegionQueue haRegionQueue, int count, int regionId) {
    Thread thread = new DoPuts(this.threadGroup, haRegionQueue, count, regionId);
    this.threads.add(thread);
    thread.start();
    return thread;
  }

  private Thread startDoTakes(HARegionQueue haRegionQueue, int count) {
    Thread thread = new DoTakes(this.threadGroup, haRegionQueue, count);
    this.threads.add(thread);
    thread.start();
    return thread;
  }

  private ConditionFactory await() {
    return Awaitility.await().atMost(2, MINUTES);
  }

  int nextDoPutsThreadNum() {
    synchronized (this.numberForThreadsLock) {
      return numberForDoPuts++;
    }
  }

  int nextDoTakesThreadNum() {
    synchronized (this.numberForThreadsLock) {
      return numberForDoTakes++;
    }
  }

  /**
   * class which does specified number of puts on the queue
   */
  private class DoPuts extends Thread {

    private final HARegionQueue regionQueue;

    private final int numberOfPuts;

    /**
     * region id can be specified to generate Thread unique events
     */
    private final int regionId;

    DoPuts(ThreadGroup threadGroup, HARegionQueue haRegionQueue, int numberOfPuts) {
      this(threadGroup, haRegionQueue, numberOfPuts, 0);
    }

    DoPuts(ThreadGroup threadGroup, HARegionQueue haRegionQueue, int numberOfPuts, int regionId) {
      super(threadGroup, "DoPuts-" + nextDoPutsThreadNum());
      this.regionQueue = haRegionQueue;
      this.numberOfPuts = numberOfPuts;
      this.regionId = regionId;
    }

    @Override
    public void run() {
      for (int i = 0; i < this.numberOfPuts; i++) {
        if (stopThreads || Thread.currentThread().isInterrupted()) {
          break;
        }
        try {
          this.regionQueue.put(new ConflatableObject("" + i, "" + i,
              new EventID(new byte[this.regionId], i, i), false, REGION));
        } catch (Exception e) {
          errorCollector.addError(e);
          break;
        }
      }
    }
  }

  /**
   * class which does a specified number of takes
   */
  private class DoTakes extends Thread {

    private final HARegionQueue regionQueue;

    private final int numberOfTakes;

    DoTakes(ThreadGroup threadGroup, HARegionQueue haRegionQueue, int numberOfTakes) {
      super(threadGroup, "DoTakes-" + nextDoTakesThreadNum());
      this.regionQueue = haRegionQueue;
      this.numberOfTakes = numberOfTakes;
    }

    @Override
    public void run() {
      for (int i = 0; i < this.numberOfTakes; i++) {
        if (stopThreads || Thread.currentThread().isInterrupted()) {
          break;
        }
        try {
          assertNotNull(this.regionQueue.take());
        } catch (Exception e) {
          errorCollector.addError(e);
          break;
        }
      }
    }
  }

}
