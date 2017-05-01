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
package org.apache.geode.modules.session.internal.filter;

import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.FilterConfig;
import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.geode.cache.CacheClosedException;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.EntryNotFoundException;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.control.ResourceManager;
import org.apache.geode.distributed.internal.DistributionConfig;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.modules.session.bootstrap.AbstractCache;
import org.apache.geode.modules.session.bootstrap.ClientServerCache;
import org.apache.geode.modules.session.bootstrap.LifecycleTypeAdapter;
import org.apache.geode.modules.session.bootstrap.PeerToPeerCache;
import org.apache.geode.modules.session.internal.common.CacheProperty;
import org.apache.geode.modules.session.internal.common.ClientServerSessionCache;
import org.apache.geode.modules.session.internal.common.PeerToPeerSessionCache;
import org.apache.geode.modules.session.internal.common.SessionCache;
import org.apache.geode.modules.session.internal.filter.attributes.AbstractSessionAttributes;
import org.apache.geode.modules.session.internal.filter.attributes.DeltaQueuedSessionAttributes;
import org.apache.geode.modules.session.internal.filter.attributes.DeltaSessionAttributes;
import org.apache.geode.modules.session.internal.filter.attributes.ImmediateSessionAttributes;
import org.apache.geode.modules.session.internal.jmx.SessionStatistics;
import org.apache.geode.modules.util.RegionHelper;

/**
 * This class implements the session management using a Gemfire distributedCache as a persistent
 * store for the session objects
 */
public class GemfireSessionManager implements SessionManager {

  private final Logger logger;

  /**
   * Prefix of init param string used to set gemfire properties
   */
  private static final String GEMFIRE_PROPERTY = DistributionConfig.GEMFIRE_PREFIX + "property.";

  /**
   * Prefix of init param string used to set gemfire distributedCache setting
   */
  private static final String GEMFIRE_CACHE = DistributionConfig.GEMFIRE_PREFIX + "cache.";

  private static final String INIT_PARAM_CACHE_TYPE = "cache-type";
  private static final String CACHE_TYPE_CLIENT_SERVER = "client-server";
  private static final String CACHE_TYPE_PEER_TO_PEER = "peer-to-peer";
  private static final String INIT_PARAM_SESSION_COOKIE_NAME = "session-cookie-name";
  private static final String INIT_PARAM_JVM_ID = "jvm-id";
  private static final String DEFAULT_JVM_ID = "default";

  private SessionCache sessionCache = null;

  /**
   * Reference to the distributed system
   */
  private AbstractCache distributedCache = null;

  /**
   * Boolean indicating whether the manager is shutting down
   */
  private boolean isStopping = false;

  /**
   * Boolean indicating whether this manager is defined in the same context (war / classloader) as
   * the filter.
   */
  private boolean isolated = false;

  /**
   * Map of wrapping GemFire session id to native session id
   */
  private final Map<String, String> nativeSessionMap = new HashMap<>();

  /**
   * MBean for statistics
   */
  private final SessionStatistics sessionStatisticsMBean;

  /**
   * This CL is used to compare against the class loader of attributes getting pulled out of the
   * cache. This variable should be set to the CL of the filter running everything.
   */
  private ClassLoader referenceClassLoader;

  private String sessionCookieName = "JSESSIONID";

  /**
   * Give this JVM a unique identifier.
   */
  private String jvmId = "default";

  /**
   * Set up properties with default values
   */
  private final EnumMap<CacheProperty, Object> properties = createPropertiesEnumMap();

  public GemfireSessionManager() {
    this.logger = LoggerFactory.getLogger(GemfireSessionManager.class.getName());
    this.sessionStatisticsMBean = new SessionStatistics();
  }

  @Override
  public void start(Object config, ClassLoader loader) {
    this.referenceClassLoader = loader;
    FilterConfig filterConfig = (FilterConfig) config;

    startDistributedSystem(filterConfig);
    initializeSessionCache(filterConfig);

    // Register MBean
    try {
      registerMBean(this.sessionStatisticsMBean);
    } catch (NamingException e) {
      this.logger.warn("Unable to register statistics MBean. Error: {}", e.getMessage(), e);
    }

    if (this.distributedCache.getClass().getClassLoader() == loader) {
      this.isolated = true;
    }

    String sessionCookieName = filterConfig.getInitParameter(INIT_PARAM_SESSION_COOKIE_NAME);
    if (sessionCookieName != null && !sessionCookieName.isEmpty()) {
      this.sessionCookieName = sessionCookieName;
      this.logger.info("Session cookie name set to: {}", this.sessionCookieName);
    }

    this.jvmId = filterConfig.getInitParameter(INIT_PARAM_JVM_ID);
    if (this.jvmId == null || this.jvmId.isEmpty()) {
      this.jvmId = DEFAULT_JVM_ID;
    }

    this.logger.info("Started GemfireSessionManager (isolated={}, jvmId={})", this.isolated,
        this.jvmId);
  }

  @Override
  public void stop() {
    this.isStopping = true;

    if (this.isolated) {
      if (this.distributedCache != null) {
        this.logger.info("Closing distributed cache - assuming isolated cache");
        this.distributedCache.close();
      }
    } else {
      this.logger.info("Not closing distributed cache - assuming common cache");
    }
  }

  @Override
  public HttpSession getSession(String id) {
    GemfireHttpSession session =
        (GemfireHttpSession) this.sessionCache.getOperatingRegion().get(id);

    if (session != null) {
      if (session.justSerialized()) {
        session.setManager(this);
        this.logger.debug("Recovered serialized session {} (jvmId={})", id,
            session.getJvmOwnerId());
      }
      this.logger.debug("Retrieved session id {}", id);
    } else {
      this.logger.debug("Session id {} not found", id);
    }
    return session;
  }

  @Override
  public HttpSession wrapSession(HttpSession nativeSession) {
    String id = generateId();
    GemfireHttpSession session = new GemfireHttpSession(id, nativeSession);

    // Set up the attribute container depending on how things are configured
    AbstractSessionAttributes attributes;
    if ("delta_queued".equals(this.properties.get(CacheProperty.SESSION_DELTA_POLICY))) {
      attributes = new DeltaQueuedSessionAttributes();
      ((DeltaQueuedSessionAttributes) attributes)
          .setReplicationTrigger((String) this.properties.get(CacheProperty.REPLICATION_TRIGGER));
    } else if ("delta_immediate".equals(this.properties.get(CacheProperty.SESSION_DELTA_POLICY))) {
      attributes = new DeltaSessionAttributes();
    } else if ("immediate".equals(this.properties.get(CacheProperty.SESSION_DELTA_POLICY))) {
      attributes = new ImmediateSessionAttributes();
    } else {
      attributes = new DeltaSessionAttributes();
      this.logger.warn("No session delta policy specified - using default of 'delta_immediate'");
    }

    attributes.setSession(session);
    attributes.setJvmOwnerId(this.jvmId);

    session.setManager(this);
    session.setAttributes(attributes);

    this.logger.debug("Creating new session {}", id);
    this.sessionCache.getOperatingRegion().put(id, session);

    this.sessionStatisticsMBean.incActiveSessions();

    return session;
  }

  @Override
  public HttpSession getWrappingSession(String nativeId) {
    HttpSession session = null;
    String gemfireId = getGemfireSessionIdFromNativeId(nativeId);

    if (gemfireId != null) {
      session = getSession(gemfireId);
    }
    return session;
  }

  @Override
  public void destroySession(String id) {
    if (!this.isStopping) {
      try {
        GemfireHttpSession session =
            (GemfireHttpSession) this.sessionCache.getOperatingRegion().get(id);
        if (session != null && session.getJvmOwnerId().equals(this.jvmId)) {
          this.logger.debug("Destroying session {}", id);
          this.sessionCache.getOperatingRegion().destroy(id);
          this.sessionStatisticsMBean.decActiveSessions();
        }
      } catch (EntryNotFoundException ignore) {
      }
    } else {
      if (this.sessionCache.isClientServer()) {
        this.logger.debug("Destroying session {}", id);
        try {
          this.sessionCache.getOperatingRegion().localDestroy(id);
        } catch (EntryNotFoundException | CacheClosedException ignore) {
          // Ignored
        }
      } else {
        GemfireHttpSession session =
            (GemfireHttpSession) this.sessionCache.getOperatingRegion().get(id);
        if (session != null) {
          session.setNativeSession(null);
        }
      }
    }

    synchronized (this.nativeSessionMap) {
      String nativeId = this.nativeSessionMap.remove(id);
      this.logger.debug("destroySession called for {} wrapping {}", id, nativeId);
    }
  }

  @Override
  public void putSession(HttpSession session) {
    this.sessionCache.getOperatingRegion().put(session.getId(), session);
    this.sessionStatisticsMBean.incRegionUpdates();
    this.nativeSessionMap.put(session.getId(),
        ((GemfireHttpSession) session).getNativeSession().getId());
  }

  @Override
  public String destroyNativeSession(String id) {
    String gemfireSessionId = getGemfireSessionIdFromNativeId(id);
    if (gemfireSessionId != null) {
      destroySession(gemfireSessionId);
    }
    return gemfireSessionId;
  }

  ClassLoader getReferenceClassLoader() {
    return this.referenceClassLoader;
  }

  @Override
  public String getSessionCookieName() {
    return this.sessionCookieName;
  }

  @Override
  public String getJvmId() {
    return this.jvmId;
  }

  private String getGemfireSessionIdFromNativeId(String nativeId) {
    if (nativeId == null) {
      return null;
    }

    for (Map.Entry<String, String> entry : this.nativeSessionMap.entrySet()) {
      if (nativeId.equals(entry.getValue())) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Start the underlying distributed system
   */
  private void startDistributedSystem(FilterConfig config) {
    // Get the distributedCache type
    final String cacheType = config.getInitParameter(INIT_PARAM_CACHE_TYPE);
    if (CACHE_TYPE_CLIENT_SERVER.equals(cacheType)) {
      this.distributedCache = ClientServerCache.getInstance();
    } else if (CACHE_TYPE_PEER_TO_PEER.equals(cacheType)) {
      this.distributedCache = PeerToPeerCache.getInstance();
    } else {
      this.logger.error("No 'cache-type' initialization param set. " + "Cache will not be started");
      return;
    }

    if (!this.distributedCache.isStarted()) {
      /*
       * Process all the init params and see if any apply to the distributed system.
       */
      for (Enumeration<String> e = config.getInitParameterNames(); e.hasMoreElements();) {
        String param = e.nextElement();
        if (!param.startsWith(GEMFIRE_PROPERTY)) {
          continue;
        }

        String gemfireProperty = param.substring(GEMFIRE_PROPERTY.length());
        this.logger.info("Setting gemfire property: {} = {}", gemfireProperty,
            config.getInitParameter(param));
        this.distributedCache.setProperty(gemfireProperty, config.getInitParameter(param));
      }

      this.distributedCache.lifecycleEvent(LifecycleTypeAdapter.START);
    }
  }

  /**
   * Initialize the distributedCache
   */
  private void initializeSessionCache(FilterConfig config) {
    // Retrieve the distributedCache
    InternalCache cache = (InternalCache) CacheFactory.getAnyInstance();
    if (cache == null) {
      throw new IllegalStateException(
          "No cache exists. Please configure either a PeerToPeerCacheLifecycleListener or ClientServerCacheLifecycleListener in the server.xml file.");
    }

    // Process all the init params and see if any apply to the distributedCache
    ResourceManager rm = cache.getResourceManager();
    for (Enumeration<String> e = config.getInitParameterNames(); e.hasMoreElements();) {
      String param = e.nextElement();

      // Ugh - don't like this non-generic stuff
      if (param.equalsIgnoreCase("criticalHeapPercentage")) {
        float val = Float.parseFloat(config.getInitParameter(param));
        rm.setCriticalHeapPercentage(val);
      }

      if (param.equalsIgnoreCase("evictionHeapPercentage")) {
        float val = Float.parseFloat(config.getInitParameter(param));
        rm.setEvictionHeapPercentage(val);
      }


      if (!param.startsWith(GEMFIRE_CACHE)) {
        continue;
      }

      String gemfireWebParam = param.substring(GEMFIRE_CACHE.length());
      this.logger.info("Setting cache parameter: {} = {}", gemfireWebParam,
          config.getInitParameter(param));
      this.properties.put(CacheProperty.valueOf(gemfireWebParam.toUpperCase()),
          config.getInitParameter(param));
    }

    // Create the appropriate session distributedCache
    if (cache.isClient()) {
      this.sessionCache = new ClientServerSessionCache((ClientCache) cache, this.properties);
    } else {
      this.sessionCache = new PeerToPeerSessionCache(cache, this.properties);
    }

    // Initialize the session distributedCache
    this.sessionCache.initialize();
  }

  /**
   * Register a bean for statistic gathering purposes
   */
  private void registerMBean(final SessionStatistics mBean) throws NamingException {
    InitialContext ctx = new InitialContext();
    try {
      MBeanServer mBeanServer = MBeanServer.class.cast(ctx.lookup("java:comp/env/jmx/runtime"));
      ObjectName objectName = new ObjectName(Constants.SESSION_STATISTICS_MBEAN_NAME);

      mBeanServer.registerMBean(mBean, objectName);
    } catch (MalformedObjectNameException | NotCompliantMBeanException
        | InstanceAlreadyExistsException | MBeanRegistrationException e) {
      this.logger.warn("Unable to register statistics MBean. Error: {}", e.getMessage(), e);
    } finally {
      ctx.close();
    }
  }

  /**
   * Generate an ID string
   */
  private String generateId() {
    return UUID.randomUUID().toString().toUpperCase() + "-GF";
  }

  AbstractCache getCache() {
    return this.distributedCache;
  }

  private EnumMap<CacheProperty, Object> createPropertiesEnumMap() {
    EnumMap<CacheProperty, Object> cacheProperties = new EnumMap<>(CacheProperty.class);
    cacheProperties.put(CacheProperty.REGION_NAME, RegionHelper.NAME + "_sessions");
    cacheProperties.put(CacheProperty.ENABLE_GATEWAY_DELTA_REPLICATION, Boolean.FALSE);
    cacheProperties.put(CacheProperty.ENABLE_GATEWAY_REPLICATION, Boolean.FALSE);
    cacheProperties.put(CacheProperty.ENABLE_DEBUG_LISTENER, Boolean.FALSE);
    cacheProperties.put(CacheProperty.STATISTICS_NAME, "gemfire_statistics");
    cacheProperties.put(CacheProperty.SESSION_DELTA_POLICY, "delta_queued");
    cacheProperties.put(CacheProperty.REPLICATION_TRIGGER, "set");
    /*
     * For REGION_ATTRIBUTES_ID and ENABLE_LOCAL_CACHE the default is different for
     * ClientServerCache and PeerToPeerCache so those values are set in the relevant constructors
     * when these properties are passed in to them.
     */
    return cacheProperties;
  }
}
