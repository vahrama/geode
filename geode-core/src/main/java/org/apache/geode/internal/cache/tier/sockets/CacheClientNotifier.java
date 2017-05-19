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

import static org.apache.geode.distributed.ConfigurationProperties.*;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;
import org.apache.shiro.subject.Subject;

import org.apache.geode.CancelException;
import org.apache.geode.DataSerializer;
import org.apache.geode.Instantiator;
import org.apache.geode.InternalGemFireError;
import org.apache.geode.StatisticsFactory;
import org.apache.geode.cache.CacheEvent;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.InterestRegistrationEvent;
import org.apache.geode.cache.InterestRegistrationListener;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionDestroyedException;
import org.apache.geode.cache.RegionExistsException;
import org.apache.geode.cache.UnsupportedVersionException;
import org.apache.geode.cache.client.internal.PoolImpl;
import org.apache.geode.cache.client.internal.PoolImpl.PoolTask;
import org.apache.geode.cache.query.CqException;
import org.apache.geode.cache.query.Query;
import org.apache.geode.cache.query.internal.DefaultQuery;
import org.apache.geode.cache.query.internal.cq.CqService;
import org.apache.geode.cache.query.internal.cq.ServerCQ;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.distributed.DistributedMember;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.DM;
import org.apache.geode.distributed.internal.DistributionConfig;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.internal.ClassLoadUtil;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.statistics.DummyStatisticsFactory;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.InternalInstantiator;
import org.apache.geode.internal.net.SocketCloser;
import org.apache.geode.internal.SystemTimer;
import org.apache.geode.internal.Version;
import org.apache.geode.internal.VersionedDataInputStream;
import org.apache.geode.internal.VersionedDataOutputStream;
import org.apache.geode.internal.cache.CacheClientStatus;
import org.apache.geode.internal.cache.CacheDistributionAdvisor;
import org.apache.geode.internal.cache.CacheServerImpl;
import org.apache.geode.internal.cache.ClientRegionEventImpl;
import org.apache.geode.internal.cache.ClientServerObserver;
import org.apache.geode.internal.cache.ClientServerObserverHolder;
import org.apache.geode.internal.cache.Conflatable;
import org.apache.geode.internal.cache.EntryEventImpl;
import org.apache.geode.internal.cache.EnumListenerEvent;
import org.apache.geode.internal.cache.EventID;
import org.apache.geode.internal.cache.FilterProfile;
import org.apache.geode.internal.cache.FilterRoutingInfo.FilterInfo;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.internal.cache.InternalCacheEvent;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.RegionEventImpl;
import org.apache.geode.internal.cache.ha.HAContainerMap;
import org.apache.geode.internal.cache.ha.HAContainerRegion;
import org.apache.geode.internal.cache.ha.HAContainerWrapper;
import org.apache.geode.internal.cache.ha.HARegionQueue;
import org.apache.geode.internal.cache.ha.ThreadIdentifier;
import org.apache.geode.internal.cache.tier.Acceptor;
import org.apache.geode.internal.cache.tier.MessageType;
import org.apache.geode.internal.cache.versions.VersionTag;
import org.apache.geode.internal.i18n.LocalizedStrings;
import org.apache.geode.internal.logging.InternalLogWriter;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.internal.logging.log4j.LocalizedMessage;
import org.apache.geode.security.AccessControl;
import org.apache.geode.security.AuthenticationFailedException;
import org.apache.geode.security.AuthenticationRequiredException;

/**
 * Class {@code CacheClientNotifier} works on the server and manages client socket connections to
 * clients requesting notification of updates and notifies them when updates occur.
 *
 * @since GemFire 3.2
 */
public class CacheClientNotifier {
  private static final Logger logger = LogService.getLogger();

  /**
   * The size of the server-to-client communication socket buffers. This can be modified using the
   * BridgeServer.SOCKET_BUFFER_SIZE system property.
   */
  private static final int socketBufferSize =
    Integer.getInteger("BridgeServer.SOCKET_BUFFER_SIZE", 32768);

  private static final long CLIENT_PING_TASK_PERIOD =
    Long.getLong(DistributionConfig.GEMFIRE_PREFIX + "serverToClientPingPeriod", 60000);

  /**
   * package-private to avoid synthetic accessor
   */
  static final long CLIENT_PING_TASK_COUNTER =
    Long.getLong(DistributionConfig.GEMFIRE_PREFIX + "serverToClientPingCounter", 3);

  private static volatile CacheClientNotifier ccnSingleton;

  /**
   * The map of known {@code CacheClientProxy} instances. Maps ClientProxyMembershipID to
   * CacheClientProxy. Note that the keys in this map are not updated when a durable client
   * reconnects. To make sure you get the updated ClientProxyMembershipID use this map to lookup the
   * CacheClientProxy and then call getProxyID on it.
   * <p>
   * NOTE: package-private to avoid synthetic accessor
   */
  final ConcurrentMap/* <ClientProxyMembershipID, CacheClientProxy> */ clientProxies =
      new ConcurrentHashMap();

  /**
   * The map of {@code CacheClientProxy} instances which are getting initialized. Maps
   * ClientProxyMembershipID to CacheClientProxy.
   */
  private final ConcurrentMap/* <ClientProxyMembershipID, CacheClientProxy> */ initClientProxies =
      new ConcurrentHashMap();

  private final Set<ClientProxyMembershipID> timedOutDurableClientProxies = new HashSet<>();

  /** the maximum number of messages that can be enqueued in a client-queue. */
  private final int maximumMessageCount;

  /**
   * the time (in seconds) after which a message in the client queue will expire.
   */
  private final int messageTimeToLive;

  /**
   * A listener which receives notifications about queues that are added or removed
   */
  private final ConnectionListener connectionListener;

  private final CacheServerStats acceptorStats;

  /**
   * The statistics for this notifier
   */
  final CacheClientNotifierStats statistics; // TODO: pass statistics into CacheClientProxy

  /**
   * The {@code InterestRegistrationListener} instances registered in this VM. This is used when
   * modifying the set of listeners.
   */
  private final Set writableInterestRegistrationListeners = new CopyOnWriteArraySet();

  /**
   * The {@code InterestRegistrationListener} instances registered in this VM. This is used to
   * provide a read-only {@code Set} of listeners.
   */
  private final Set readableInterestRegistrationListeners =
    Collections.unmodifiableSet(this.writableInterestRegistrationListeners);

  private final Map<String, DefaultQuery> compiledQueries = new ConcurrentHashMap<>();

  private final Object lockIsCompiledQueryCleanupThreadStarted = new Object();

  private final SocketCloser socketCloser;

  /** package-private to avoid synthetic accessor */
  final Set blackListedClients = new CopyOnWriteArraySet();

  /**
   * haContainer can hold either the name of the client-messages-region (in case of eviction
   * policies "mem" or "entry") or an instance of HashMap (in case of eviction policy "none"). In
   * both the cases, it'll store HAEventWrapper as its key and ClientUpdateMessage as its value.
   */
  private volatile HAContainerWrapper haContainer;

  private volatile boolean isCompiledQueryCleanupThreadStarted = false;

  /**
   * The GemFire {@code InternalCache}. Note that since this is a singleton class you should not use
   * a direct reference to cache in CacheClientNotifier code. Instead, you should always use
   * {@code getCache()}
   */
  private InternalCache cache; // TODO: fix synchronization of cache

  private InternalLogWriter logWriter;

  /**
   * The GemFire security {@code LogWriter}
   */
  private InternalLogWriter securityLogWriter;

  private SystemTimer.SystemTimerTask clientPingTask; // TODO: fix synchronization of clientPingTask

  /**
   * Factory method to construct a CacheClientNotifier {@code CacheClientNotifier} instance.
   *
   * @param cache The GemFire {@code InternalCache}
   * @return A {@code CacheClientNotifier} instance
   */
  public static synchronized CacheClientNotifier getInstance(InternalCache cache,
      CacheServerStats acceptorStats, int maximumMessageCount, int messageTimeToLive,
      ConnectionListener listener, List overflowAttributesList, boolean isGatewayReceiver) {
    if (ccnSingleton == null) {
      ccnSingleton = new CacheClientNotifier(cache, acceptorStats, maximumMessageCount,
          messageTimeToLive, listener, isGatewayReceiver);
    }

    if (!isGatewayReceiver && ccnSingleton.getHaContainer() == null) {
      // Gateway receiver might have create CCN instance without HaContainer
      // In this case, the HaContainer should be lazily created here
      ccnSingleton.initHaContainer(overflowAttributesList);
    }
    return ccnSingleton;
  }

  public static CacheClientNotifier getInstance() {
    return ccnSingleton;
  }

  /**
   * @param cache The GemFire {@code InternalCache}
   * @param listener a listener which should receive notifications abouts queues being added or
   *        removed.
   */
  private CacheClientNotifier(InternalCache cache, CacheServerStats acceptorStats,
      int maximumMessageCount, int messageTimeToLive, ConnectionListener listener,
      boolean isGatewayReceiver) {
    // Set the Cache
    setCache(cache);
    this.acceptorStats = acceptorStats;
    // we only need one thread per client and wait 50ms for close
    this.socketCloser = new SocketCloser(1, 50);

    // Set the LogWriter
    this.logWriter = (InternalLogWriter) cache.getLogger();

    this.connectionListener = listener;

    // Set the security LogWriter
    this.securityLogWriter = (InternalLogWriter) cache.getSecurityLogger();

    this.maximumMessageCount = maximumMessageCount;
    this.messageTimeToLive = messageTimeToLive;

    // Initialize the statistics
    StatisticsFactory factory;
    if (isGatewayReceiver) {
      factory = new DummyStatisticsFactory();
    } else {
      factory = getCache().getDistributedSystem();
    }
    this.statistics = new CacheClientNotifierStats(factory);

    // Schedule task to periodically ping clients.
    scheduleClientPingTask();
  }

  /**
   * Writes a given message to the output stream
   *
   * @param dos the {@code DataOutputStream} to use for writing the message
   * @param type a byte representing the message type
   * @param message the message to be written; can be null
   */
  private void writeMessage(DataOutputStream dos, byte type, String message, Version clientVersion)
      throws IOException {
    writeMessage(dos, type, message, clientVersion, (byte) 0x00, 0);
  }

  private void writeMessage(DataOutputStream dos, byte type, String message, Version clientVersion,
      byte epType, int qSize) throws IOException {

    // write the message type
    dos.writeByte(type);

    // dummy epType
    dos.writeByte(epType);
    // dummy qSize
    dos.writeInt(qSize);

    String msg = message;
    if (msg == null) {
      msg = "";
    }
    dos.writeUTF(msg);
    if (clientVersion != null && clientVersion.compareTo(Version.GFE_61) >= 0) {
      // get all the instantiators.
      Instantiator[] instantiators = InternalInstantiator.getInstantiators();
      Map instantiatorMap = new HashMap();
      if (instantiators != null && instantiators.length > 0) {
        for (Instantiator instantiator : instantiators) {
          List<String> instantiatorAttributes = new ArrayList<>();
          instantiatorAttributes.add(instantiator.getClass().toString().substring(6));
          instantiatorAttributes.add(instantiator.getInstantiatedClass().toString().substring(6));
          instantiatorMap.put(instantiator.getId(), instantiatorAttributes);
        }
      }
      DataSerializer.writeHashMap(instantiatorMap, dos);

      // get all the dataserializers.
      DataSerializer[] dataSerializers = InternalDataSerializer.getSerializers();
      Map<Integer, List<String>> dsToSupportedClasses = new HashMap<>();
      Map<Integer, String> dataSerializersMap = new HashMap<>();
      if (dataSerializers != null && dataSerializers.length > 0) {
        for (DataSerializer dataSerializer : dataSerializers) {
          dataSerializersMap.put(dataSerializer.getId(),
              dataSerializer.getClass().toString().substring(6));
          if (clientVersion.compareTo(Version.GFE_6516) >= 0) {
            List<String> supportedClassNames = new ArrayList<>();
            for (Class clazz : dataSerializer.getSupportedClasses()) {
              supportedClassNames.add(clazz.getName());
            }
            dsToSupportedClasses.put(dataSerializer.getId(), supportedClassNames);
          }
        }
      }
      DataSerializer.writeHashMap(dataSerializersMap, dos);
      if (clientVersion.compareTo(Version.GFE_6516) >= 0) {
        DataSerializer.writeHashMap(dsToSupportedClasses, dos);
      }
    }
    dos.flush();
  }

  /**
   * Writes an exception message to the socket
   *
   * @param dos the {@code DataOutputStream} to use for writing the message
   * @param type a byte representing the exception type
   * @param ex the exception to be written; should not be null
   */
  private void writeException(DataOutputStream dos, byte type, Exception ex, Version clientVersion)
      throws IOException {
    writeMessage(dos, type, ex.toString(), clientVersion);
  }

  /**
   * Registers a new client updater that wants to receive updates with this server.
   *
   * @param socket The socket over which the server communicates with the client.
   */
  public void registerClient(Socket socket, boolean isPrimary, long acceptorId,
      boolean notifyBySubscription) throws IOException {
    // Since no remote ports were specified in the message, wait for them.
    long startTime = this.statistics.startTime();
    DataInputStream dis = new DataInputStream(socket.getInputStream());
    DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

    // Read the client version
    short clientVersionOrdinal = Version.readOrdinal(dis);
    Version clientVersion = null;
    try {
      clientVersion = Version.fromOrdinal(clientVersionOrdinal, true);
      if (logger.isDebugEnabled()) {
        logger.debug("{}: Registering client with version: {}", this, clientVersion);
      }
    } catch (UnsupportedVersionException e) {
      SocketAddress sa = socket.getRemoteSocketAddress();
      UnsupportedVersionException uve = e;
      if (sa != null) {
        String sInfo = " Client: " + sa + ".";
        uve = new UnsupportedVersionException(e.getMessage() + sInfo);
      }
      logger.warn(
          LocalizedMessage.create(
              LocalizedStrings.CacheClientNotifier_CACHECLIENTNOTIFIER_CAUGHT_EXCEPTION_ATTEMPTING_TO_CLIENT),
          uve);
      writeException(dos, Acceptor.UNSUCCESSFUL_SERVER_TO_CLIENT, uve, clientVersion);
      return;
    }

    // Read and ignore the reply code. This is used on the client to server handshake.
    dis.readByte(); // replyCode

    if (Version.GFE_57.compareTo(clientVersion) <= 0) {
      if (Version.CURRENT.compareTo(clientVersion) > 0) {
        dis = new VersionedDataInputStream(dis, clientVersion);
        dos = new VersionedDataOutputStream(dos, clientVersion);
      }
      registerGFEClient(dis, dos, socket, isPrimary, startTime, clientVersion, acceptorId,
          notifyBySubscription);
    } else {
      Exception e = new UnsupportedVersionException(clientVersionOrdinal);
      throw new IOException(e.toString());
    }
  }

  private void registerGFEClient(DataInputStream dis, DataOutputStream dos, Socket socket,
      boolean isPrimary, long startTime, Version clientVersion, long acceptorId,
      boolean notifyBySubscription) throws IOException {
    // Read the ports and throw them away. We no longer need them
    int numberOfPorts = dis.readInt();
    for (int i = 0; i < numberOfPorts; i++) {
      dis.readInt();
    }
    // Read the handshake identifier and convert it to a string member id
    ClientProxyMembershipID proxyID = null;
    try {
      proxyID = ClientProxyMembershipID.readCanonicalized(dis);
      if (getBlacklistedClient().contains(proxyID)) {
        writeException(dos, HandShake.REPLY_INVALID,
            new Exception("This client is blacklisted by server"), clientVersion);
        return;
      }
      CacheClientProxy proxy = getClientProxy(proxyID);
      DistributedMember member = proxyID.getDistributedMember();

      DistributedSystem system = getCache().getDistributedSystem();
      Properties sysProps = system.getProperties();
      String authenticator = sysProps.getProperty(SECURITY_CLIENT_AUTHENTICATOR);

      byte clientConflation;
      if (clientVersion.compareTo(Version.GFE_603) >= 0) {
        byte[] overrides = HandShake.extractOverrides(new byte[] {(byte) dis.read()});
        clientConflation = overrides[0];
      } else {
        clientConflation = (byte) dis.read();
      }

      switch (clientConflation) {
        case HandShake.CONFLATION_DEFAULT:
        case HandShake.CONFLATION_OFF:
        case HandShake.CONFLATION_ON:
          break;
        default:
          writeException(dos, HandShake.REPLY_INVALID,
              new IllegalArgumentException("Invalid conflation byte"), clientVersion);
          return;
      }

      proxy = registerClient(socket, proxyID, proxy, isPrimary, clientConflation, clientVersion,
          acceptorId, notifyBySubscription);

      Properties credentials = HandShake.readCredentials(dis, dos, system);
      if (credentials != null && proxy != null) {
        if (this.securityLogWriter.fineEnabled()) {
          this.securityLogWriter
              .fine("CacheClientNotifier: verifying credentials for proxyID: " + proxyID);
        }
        Object subject = HandShake.verifyCredentials(authenticator, credentials,
            system.getSecurityProperties(), this.logWriter, this.securityLogWriter, member);
        if (subject instanceof Principal) {
          Principal principal = (Principal) subject;
          if (this.securityLogWriter.fineEnabled()) {
            this.securityLogWriter
                .fine("CacheClientNotifier: successfully verified credentials for proxyID: "
                    + proxyID + " having principal: " + principal.getName());
          }

          String postAuthzFactoryName = sysProps.getProperty(SECURITY_CLIENT_ACCESSOR_PP);
          AccessControl authzCallback = null;
          if (postAuthzFactoryName != null && !postAuthzFactoryName.isEmpty()) {
            Method authzMethod = ClassLoadUtil.methodFromName(postAuthzFactoryName);
            authzCallback = (AccessControl) authzMethod.invoke(null, (Object[]) null);
            authzCallback.init(principal, member, this.getCache());
          }
          proxy.setPostAuthzCallback(authzCallback);
        } else if (subject instanceof Subject) {
          proxy.setSubject((Subject) subject);
        }
      }
    } catch (ClassNotFoundException e) {
      throw new IOException(
          LocalizedStrings.CacheClientNotifier_CLIENTPROXYMEMBERSHIPID_OBJECT_COULD_NOT_BE_CREATED_EXCEPTION_OCCURRED_WAS_0
              .toLocalizedString(e));
    } catch (AuthenticationRequiredException ex) {
      this.securityLogWriter.warning(
          LocalizedStrings.CacheClientNotifier_AN_EXCEPTION_WAS_THROWN_FOR_CLIENT_0_1,
          new Object[] {proxyID, ex});
      writeException(dos, HandShake.REPLY_EXCEPTION_AUTHENTICATION_REQUIRED, ex, clientVersion);
      return;
    } catch (AuthenticationFailedException ex) {
      this.securityLogWriter.warning(
          LocalizedStrings.CacheClientNotifier_AN_EXCEPTION_WAS_THROWN_FOR_CLIENT_0_1,
          new Object[] {proxyID, ex});
      writeException(dos, HandShake.REPLY_EXCEPTION_AUTHENTICATION_FAILED, ex, clientVersion);
      return;
    } catch (CacheException e) {
      logger.warn(LocalizedMessage.create(
          LocalizedStrings.CacheClientNotifier_0_REGISTERCLIENT_EXCEPTION_ENCOUNTERED_IN_REGISTRATION_1,
          new Object[] {this, e}), e);
      throw new IOException(
          LocalizedStrings.CacheClientNotifier_EXCEPTION_OCCURRED_WHILE_TRYING_TO_REGISTER_INTEREST_DUE_TO_0
              .toLocalizedString(e.getMessage()),
          e);
    } catch (Exception ex) {
      logger.warn(LocalizedMessage.create(
          LocalizedStrings.CacheClientNotifier_AN_EXCEPTION_WAS_THROWN_FOR_CLIENT_0_1,
          new Object[] {proxyID, ""}), ex);
      writeException(dos, Acceptor.UNSUCCESSFUL_SERVER_TO_CLIENT, ex, clientVersion);
      return;
    }

    this.statistics.endClientRegistration(startTime);
  }

  /**
   * Registers a new client that wants to receive updates with this server.
   *
   * @param socket The socket over which the server communicates with the client.
   * @param proxyId The distributed member id of the client being registered
   * @param proxy The {@code CacheClientProxy} of the given {@code proxyId}
   *
   * @return CacheClientProxy for the registered client
   */
  private CacheClientProxy registerClient(Socket socket, ClientProxyMembershipID proxyId,
      CacheClientProxy proxy, boolean isPrimary, byte clientConflation, Version clientVersion,
      long acceptorId, boolean notifyBySubscription) throws IOException, CacheException {

    // Initialize the socket
    socket.setTcpNoDelay(true);
    socket.setSendBufferSize(CacheClientNotifier.socketBufferSize);
    socket.setReceiveBufferSize(CacheClientNotifier.socketBufferSize);

    if (logger.isDebugEnabled()) {
      logger.debug(
          "CacheClientNotifier: Initialized server-to-client socket with send buffer size: {} bytes and receive buffer size: {} bytes",
          socket.getSendBufferSize(), socket.getReceiveBufferSize());
    }

    // Determine whether the client is durable or not.
    boolean clientIsDurable = proxyId.isDurable();
    if (logger.isDebugEnabled()) {
      if (clientIsDurable) {
        logger.debug("CacheClientNotifier: Attempting to register durable client: {}",
            proxyId.getDurableId());
      } else {
        logger.debug("CacheClientNotifier: Attempting to register non-durable client");
      }
    }

    byte epType = 0x00;
    int qSize = 0;
    byte responseByte = Acceptor.SUCCESSFUL_SERVER_TO_CLIENT;
    String unsuccessfulMsg = null;
    boolean successful = true;
    if (clientIsDurable) {
      if (proxy == null) {
        if (isTimedOut(proxyId)) {
          qSize = PoolImpl.PRIMARY_QUEUE_TIMED_OUT;
        } else {
          qSize = PoolImpl.PRIMARY_QUEUE_NOT_AVAILABLE;
        }
        // No proxy exists for this durable client. It must be created.
        if (logger.isDebugEnabled()) {
          logger.debug(
              "CacheClientNotifier: No proxy exists for durable client with id {}. It must be created.",
              proxyId.getDurableId());
        }
        proxy = new CacheClientProxy(this, socket, proxyId, isPrimary, clientConflation,
            clientVersion, acceptorId, notifyBySubscription);
        successful = this.initializeProxy(proxy);
      } else {
        if (proxy.isPrimary()) {
          epType = (byte) 2;
        } else {
          epType = (byte) 1;
        }
        qSize = proxy.getQueueSize();
        // A proxy exists for this durable client. It must be reinitialized.
        if (proxy.isPaused()) {
          if (CacheClientProxy.testHook != null) {
            CacheClientProxy.testHook.doTestHook("CLIENT_PRE_RECONNECT");
          }
          if (proxy.lockDrain()) {
            try {
              if (logger.isDebugEnabled()) {
                logger.debug(
                    "CacheClientNotifier: A proxy exists for durable client with id {}. This proxy will be reinitialized: {}",
                    proxyId.getDurableId(), proxy);
              }
              this.statistics.incDurableReconnectionCount();
              proxy.getProxyID().updateDurableTimeout(proxyId.getDurableTimeout());
              proxy.reinitialize(socket, proxyId, this.getCache(), isPrimary, clientConflation,
                  clientVersion);
              proxy.setMarkerEnqueued(true);
              if (CacheClientProxy.testHook != null) {
                CacheClientProxy.testHook.doTestHook("CLIENT_RECONNECTED");
              }
            } finally {
              proxy.unlockDrain();
            }
          } else {
            unsuccessfulMsg =
                LocalizedStrings.CacheClientNotifier_COULD_NOT_CONNECT_DUE_TO_CQ_BEING_DRAINED
                    .toLocalizedString();
            logger.warn(unsuccessfulMsg);
            responseByte = HandShake.REPLY_REFUSED;
            if (CacheClientProxy.testHook != null) {
              CacheClientProxy.testHook.doTestHook("CLIENT_REJECTED_DUE_TO_CQ_BEING_DRAINED");
            }
          }
        } else {
          // The existing proxy is already running (which means that another
          // client is already using this durable id.
          unsuccessfulMsg =
              LocalizedStrings.CacheClientNotifier_CACHECLIENTNOTIFIER_THE_REQUESTED_DURABLE_CLIENT_HAS_THE_SAME_IDENTIFIER__0__AS_AN_EXISTING_DURABLE_CLIENT__1__DUPLICATE_DURABLE_CLIENTS_ARE_NOT_ALLOWED
                  .toLocalizedString(proxyId.getDurableId(), proxy);
          logger.warn(unsuccessfulMsg);
          // Set the unsuccessful response byte.
          responseByte = HandShake.REPLY_EXCEPTION_DUPLICATE_DURABLE_CLIENT;
        }
      }
    } else {
      CacheClientProxy staleClientProxy = this.getClientProxy(proxyId);
      boolean toCreateNewProxy = true;
      if (staleClientProxy != null) {
        if (staleClientProxy.isConnected() && staleClientProxy.getSocket().isConnected()) {
          successful = false;
          toCreateNewProxy = false;
        } else {
          // A proxy exists for this non-durable client. It must be closed.
          if (logger.isDebugEnabled()) {
            logger.debug(
                "CacheClientNotifier: A proxy exists for this non-durable client. It must be closed.");
          }
          if (staleClientProxy.startRemoval()) {
            staleClientProxy.waitRemoval();
          } else {
            staleClientProxy.close(false, false); // do not check for queue, just close it
            removeClientProxy(staleClientProxy); // remove old proxy from proxy set
          }
        }
      } // non-null stale proxy

      if (toCreateNewProxy) {
        // Create the new proxy for this non-durable client
        proxy = new CacheClientProxy(this, socket, proxyId, isPrimary, clientConflation,
            clientVersion, acceptorId, notifyBySubscription);
        successful = this.initializeProxy(proxy);
      }
    }

    if (!successful) {
      proxy = null;
      responseByte = HandShake.REPLY_REFUSED;
      unsuccessfulMsg =
          LocalizedStrings.CacheClientNotifier_CACHECLIENTNOTIFIER_A_PREVIOUS_CONNECTION_ATTEMPT_FROM_THIS_CLIENT_IS_STILL_BEING_PROCESSED__0
              .toLocalizedString(proxyId);
    }

    // Tell the client that the proxy has been registered using the response
    // byte. This byte will be read on the client by the CacheClientUpdater to
    // determine whether the registration was successful. The times when
    // registration is unsuccessful currently are if a duplicate durable client
    // is attempted to be registered or authentication fails.
    try {
      DataOutputStream dos =
          new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
      // write the message type, message length and the error message (if any)
      writeMessage(dos, responseByte, unsuccessfulMsg, clientVersion, epType, qSize);
    } catch (IOException ioe) {// remove the added proxy if we get IOException.
      if (proxy != null) {
        boolean keepProxy = proxy.close(false, false); // do not check for queue, just close it
        if (!keepProxy) {
          removeClientProxy(proxy);
        }
      }
      throw ioe;
    }

    if (unsuccessfulMsg != null && logger.isDebugEnabled()) {
      logger.debug(unsuccessfulMsg);
    }

    // If the client is not durable, start its message processor
    // Starting it here (instead of in the CacheClientProxy constructor)
    // will ensure that the response byte is sent to the client before
    // the marker message. If the client is durable, the message processor
    // is not started until the clientReady message is received.
    if (!clientIsDurable && proxy != null && responseByte == Acceptor.SUCCESSFUL_SERVER_TO_CLIENT) {
      // The startOrResumeMessageDispatcher tests if the proxy is a primary.
      // If this is a secondary proxy, the dispatcher is not started.
      // The false parameter signifies that a marker message has not already been
      // processed. This will generate and send one.
      proxy.startOrResumeMessageDispatcher(false);
    }

    if (responseByte == Acceptor.SUCCESSFUL_SERVER_TO_CLIENT) {
      if (logger.isDebugEnabled()) {
        logger.debug("CacheClientNotifier: Successfully registered {}", proxy);
      }
    } else {
      logger.warn(LocalizedMessage.create(
          LocalizedStrings.CacheClientNotifier_CACHECLIENTNOTIFIER_UNSUCCESSFULLY_REGISTERED_CLIENT_WITH_IDENTIFIER__0,
          proxyId));
    }
    return proxy;
  }

  private boolean initializeProxy(CacheClientProxy proxy) throws CacheException {
    if (!this.isProxyInInitializationMode(proxy)) {
      if (logger.isDebugEnabled()) {
        logger.debug("Initializing proxy: {}", proxy);
      }
      try {
        // Add client proxy to initialization list. This has to be done before
        // the queue is created so that events can be buffered here for delivery
        // to the queue once it's initialized (bug #41681 and others)
        addClientInitProxy(proxy);
        proxy.initializeMessageDispatcher();
        // Initialization success. Add to client proxy list.
        addClientProxy(proxy);
        return true;
      } catch (RegionExistsException ree) {
        if (logger.isDebugEnabled()) {
          String name = ree.getRegion() != null ? ree.getRegion().getFullPath() : "null region";
          logger.debug("Found RegionExistsException while initializing proxy. Region name: {}",
              name);
        }
        // This will return false;
      } finally {
        removeClientInitProxy(proxy);
      }
    }
    return false;
  }

  /**
   * Makes Primary to this CacheClientProxy and start the dispatcher of the CacheClientProxy
   *
   * @param isClientReady Whether the marker has already been processed. This value helps determine
   *        whether to start the dispatcher.
   */
  public void makePrimary(ClientProxyMembershipID proxyId, boolean isClientReady) {
    CacheClientProxy proxy = getClientProxy(proxyId);
    if (proxy != null) {
      proxy.setPrimary(true);

      /*
       * If the client represented by this proxy has: - already processed the marker message
       * (meaning the client is failing over to this server as its primary) <or> - is not durable
       * (meaning the marker message is being processed automatically
       *
       * Then, start or resume the dispatcher. Otherwise, let the clientReady message start the
       * dispatcher. See CacheClientProxy.startOrResumeMessageDispatcher if
       * (!proxy._messageDispatcher.isAlive()) {
       */
      if (isClientReady || !proxy.isDurable()) {
        if (logger.isDebugEnabled()) {
          logger.debug("CacheClientNotifier: Notifying proxy to start dispatcher for: {}", proxy);
        }
        proxy.startOrResumeMessageDispatcher(false);
      }
    } else {
      throw new InternalGemFireError("No cache client proxy on this node for proxyId " + proxyId);
    }
  }

  /**
   * Adds or updates entry in the dispatched message map when client sends an ack.
   *
   * @return success
   */
  public boolean processDispatchedMessage(ClientProxyMembershipID proxyId, EventID eid) {
    boolean success = false;
    CacheClientProxy proxy = getClientProxy(proxyId);
    if (proxy != null) {
      HARegionQueue haRegionQueue = proxy.getHARegionQueue();
      haRegionQueue.addDispatchedMessage(
          new ThreadIdentifier(eid.getMembershipID(), eid.getThreadID()), eid.getSequenceID());
      success = true;
    }
    return success;
  }

  /**
   * Sets keepalive on the proxy of the given membershipID
   *
   * @param membershipID Uniquely identifies the client pool
   * @since GemFire 5.7
   */
  public void setKeepAlive(ClientProxyMembershipID membershipID, boolean keepAlive) {
    if (logger.isDebugEnabled()) {
      logger.debug("CacheClientNotifier: setKeepAlive client: {}", membershipID);
    }
    CacheClientProxy proxy = getClientProxy(membershipID);
    if (proxy != null) {
      proxy.setKeepAlive(keepAlive);
    }
  }

  /**
   * Unregisters an existing client from this server.
   *
   * @param memberId Uniquely identifies the client
   */
  void unregisterClient(ClientProxyMembershipID memberId, boolean normalShutdown) {
    if (logger.isDebugEnabled()) {
      logger.debug("CacheClientNotifier: Unregistering all clients with member id: {}", memberId);
    }
    CacheClientProxy proxy = getClientProxy(memberId);
    if (proxy != null) {
      final boolean isTraceEnabled = logger.isTraceEnabled();
      if (isTraceEnabled) {
        logger.trace("CacheClientNotifier: Potential client: {}", proxy);
      }

      // If the proxy's member id is the same as the input member id, add
      // it to the set of dead proxies.
      if (!proxy.startRemoval()) {
        if (isTraceEnabled) {
          logger.trace("CacheClientNotifier: Potential client: {} matches {}", proxy, memberId);
        }
        closeDeadProxies(Collections.singletonList(proxy), normalShutdown);
      }
    }
  }

  /**
   * The client represented by the proxyId is ready to receive updates.
   */
  public void readyForEvents(ClientProxyMembershipID proxyId) {
    CacheClientProxy proxy = getClientProxy(proxyId);
    if (proxy == null) {
      // TODO: log a message
    } else {
      // False signifies that a marker message has not already been processed.
      // Generate and send one.
      proxy.startOrResumeMessageDispatcher(false);
    }
  }

  private ClientUpdateMessageImpl constructClientMessage(InternalCacheEvent event) {
    ClientUpdateMessageImpl clientMessage = null;
    EnumListenerEvent operation = event.getEventType();

    try {
      clientMessage = initializeMessage(operation, event);
    } catch (Exception e) {
      logger.fatal(LocalizedMessage.create(
          LocalizedStrings.CacheClientNotifier_CANNOT_NOTIFY_CLIENTS_TO_PERFORM_OPERATION_0_ON_EVENT_1,
          new Object[] {operation, event}), e);
    }
    return clientMessage;
  }

  /**
   * notify interested clients of the given cache event. The event should have routing information
   * in it that determines which clients will receive the event.
   */
  public static void notifyClients(InternalCacheEvent event) {
    CacheClientNotifier instance = getInstance();
    if (instance != null) {
      instance.singletonNotifyClients(event, null);
    }
  }

  /**
   * notify interested clients of the given cache event using the given update message. The event
   * should have routing information in it that determines which clients will receive the event.
   */
  public static void notifyClients(InternalCacheEvent event,
      ClientUpdateMessage clientUpdateMessage) {
    CacheClientNotifier instance = getInstance();
    if (instance != null) {
      instance.singletonNotifyClients(event, clientUpdateMessage);
    }
  }

  private void singletonNotifyClients(InternalCacheEvent event,
      ClientUpdateMessage clientUpdateMessage) {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    final boolean isTraceEnabled = logger.isTraceEnabled();

    FilterInfo filterInfo = event.getLocalFilterInfo();

    FilterProfile regionProfile = ((LocalRegion) event.getRegion()).getFilterProfile();
    if (filterInfo != null) {
      // if the routing was made using an old profile we need to recompute it
      if (isTraceEnabled) {
        logger.trace("Event isOriginRemote={}", event.isOriginRemote());
      }
    }

    if ((filterInfo == null
        || (filterInfo.getCQs() == null && filterInfo.getInterestedClients() == null
            && filterInfo.getInterestedClientsInv() == null))) {
      return;
    }

    long startTime = this.statistics.startTime();

    ClientUpdateMessageImpl clientMessage;
    if (clientUpdateMessage == null) {
      clientMessage = constructClientMessage(event);
    } else {
      clientMessage = (ClientUpdateMessageImpl) clientUpdateMessage;
    }
    if (clientMessage == null) {
      return;
    }

    // Holds the clientIds to which filter message needs to be sent.
    Set<ClientProxyMembershipID> filterClients = new HashSet<>();

    // Add CQ info.
    if (filterInfo.getCQs() != null) {
      for (Map.Entry<Long, Integer> e : filterInfo.getCQs().entrySet()) {
        Long cqID = e.getKey();
        String cqName = regionProfile.getRealCqID(cqID);
        if (cqName == null) {
          continue;
        }
        ServerCQ cq = regionProfile.getCq(cqName);
        if (cq != null) {
          ClientProxyMembershipID id = cq.getClientProxyId();
          filterClients.add(id);
          if (isDebugEnabled) {
            logger.debug("Adding cq routing info to message for id: {} and cq: {}", id, cqName);
          }

          clientMessage.addClientCq(id, cq.getName(), e.getValue());
        }
      }
    }

    // Add interestList info.
    if (filterInfo.getInterestedClientsInv() != null) {
      Set<Object> rawIDs = regionProfile.getRealClientIDs(filterInfo.getInterestedClientsInv());
      Set<ClientProxyMembershipID> ids = getProxyIDs(rawIDs, true);
      if (ids.remove(event.getContext())) { // don't send to member of origin
        CacheClientProxy ccp = getClientProxy(event.getContext());
        if (ccp != null) {
          ccp.getStatistics().incMessagesNotQueuedOriginator();
        }
      }
      if (!ids.isEmpty()) {
        if (isTraceEnabled) {
          logger.trace("adding invalidation routing to message for {}", ids);
        }
        clientMessage.addClientInterestList(ids, false);
        filterClients.addAll(ids);
      }
    }
    if (filterInfo.getInterestedClients() != null) {
      Set<Object> rawIDs = regionProfile.getRealClientIDs(filterInfo.getInterestedClients());
      Set<ClientProxyMembershipID> ids = getProxyIDs(rawIDs, true);
      if (ids.remove(event.getContext())) { // don't send to member of origin
        CacheClientProxy ccp = getClientProxy(event.getContext());
        if (ccp != null) {
          ccp.getStatistics().incMessagesNotQueuedOriginator();
        }
      }
      if (!ids.isEmpty()) {
        if (isTraceEnabled) {
          logger.trace("adding routing to message for {}", ids);
        }
        clientMessage.addClientInterestList(ids, true);
        filterClients.addAll(ids);
      }
    }

    Conflatable conflatable;

    if (clientMessage instanceof ClientTombstoneMessage) {
      // bug #46832 - HAEventWrapper deserialization can't handle subclasses
      // of ClientUpdateMessageImpl, so don't wrap them
      conflatable = clientMessage;
      // Remove clients older than 70 from the filterClients if the message is
      // ClientTombstoneMessage. Fix for #46591.
      Object[] objects = filterClients.toArray();
      for (Object id : objects) {
        CacheClientProxy ccp = getClientProxy((ClientProxyMembershipID) id, true);
        if (ccp != null && ccp.getVersion().compareTo(Version.GFE_70) < 0) {
          filterClients.remove(id);
        }
      }
    } else {
      HAEventWrapper wrapper = new HAEventWrapper(clientMessage);
      // Set the putInProgress flag to true before starting the put on proxy's
      // HA queues. Nowhere else, this flag is being set to true.
      wrapper.setPutInProgress(true);
      conflatable = wrapper;
    }

    singletonRouteClientMessage(conflatable, filterClients);

    this.statistics.endEvent(startTime);

    // Cleanup destroyed events in CQ result cache.
    // While maintaining the CQ results key caching. the destroy event
    // keys are marked as destroyed instead of removing them, this is
    // to take care, arrival of duplicate events. The key marked as
    // destroyed are removed after the event is placed in clients HAQueue.
    if (filterInfo.filterProcessedLocally) {
      removeDestroyTokensFromCqResultKeys(event, filterInfo);
    }
  }

  private void removeDestroyTokensFromCqResultKeys(InternalCacheEvent event,
      FilterInfo filterInfo) {
    FilterProfile regionProfile = ((LocalRegion) event.getRegion()).getFilterProfile();
    if (event.getOperation().isEntry() && filterInfo.getCQs() != null) {
      EntryEventImpl entryEvent = (EntryEventImpl) event;
      for (Map.Entry<Long, Integer> e : filterInfo.getCQs().entrySet()) {
        Long cqID = e.getKey();
        String cqName = regionProfile.getRealCqID(cqID);
        if (cqName != null) {
          ServerCQ cq = regionProfile.getCq(cqName);
          if (cq != null && e.getValue().equals(MessageType.LOCAL_DESTROY)) {
            cq.removeFromCqResultKeys(entryEvent.getKey(), true);
          }
        }
      }
    }
  }

  /**
   * delivers the given message to all proxies for routing. The message should already have client
   * interest established, or override the isClientInterested method to implement its own routing
   */
  public static void routeClientMessage(Conflatable clientMessage) {
    CacheClientNotifier instance = getInstance();
    if (instance != null) {
      // ok to use keySet here because all we do is call getClientProxy with these keys
      instance.singletonRouteClientMessage(clientMessage, instance.clientProxies.keySet());
    }
  }

  /**
   * this is for server side registration of client queue
   */
  static void routeSingleClientMessage(ClientUpdateMessage clientMessage,
      ClientProxyMembershipID clientProxyMembershipId) {
    CacheClientNotifier instance = getInstance();
    if (instance != null) {
      instance.singletonRouteClientMessage(clientMessage,
          Collections.singleton(clientProxyMembershipId));
    }
  }

  private void singletonRouteClientMessage(Conflatable conflatable,
      Collection<ClientProxyMembershipID> filterClients) {

    this.cache.getCancelCriterion().checkCancelInProgress(null);

    List<CacheClientProxy> deadProxies = null;
    for (ClientProxyMembershipID clientId : filterClients) {
      CacheClientProxy proxy = this.getClientProxy(clientId, true);
      if (proxy != null) {
        if (proxy.isAlive() || proxy.isPaused() || proxy.isConnected() || proxy.isDurable()) {
          proxy.deliverMessage(conflatable);
        } else {
          proxy.getStatistics().incMessagesFailedQueued();
          if (deadProxies == null) {
            deadProxies = new ArrayList<>();
          }
          deadProxies.add(proxy);
        }
        this.blackListSlowReceiver(proxy);
      }
    }
    checkAndRemoveFromClientMessagesRegion(conflatable);
    // Remove any dead clients from the clients to notify
    if (deadProxies != null) {
      closeDeadProxies(deadProxies, false);
    }
  }

  /**
   * processes the given collection of durable and non-durable client identifiers, returning a
   * collection of non-durable identifiers of clients connected to this VM
   */
  Set<ClientProxyMembershipID> getProxyIDs(Set mixedDurableAndNonDurableIDs) {
    return getProxyIDs(mixedDurableAndNonDurableIDs, false);
  }

  /**
   * processes the given collection of durable and non-durable client identifiers, returning a
   * collection of non-durable identifiers of clients connected to this VM. This version can check
   * for proxies in initialization as well as fully initialized proxies.
   */
  private Set<ClientProxyMembershipID> getProxyIDs(Set mixedDurableAndNonDurableIDs,
      boolean proxyInInitMode) {
    Set<ClientProxyMembershipID> result = new HashSet<>();
    for (Object id : mixedDurableAndNonDurableIDs) {
      if (id instanceof String) {
        CacheClientProxy clientProxy = getClientProxy((String) id, true);
        if (clientProxy != null) {
          result.add(clientProxy.getProxyID());
        }
      } else {
        // try to canonicalize the ID.
        CacheClientProxy proxy = getClientProxy((ClientProxyMembershipID) id, true);
        if (proxy != null) {
          result.add(proxy.getProxyID());
        }
      }
    }
    return result;
  }

  private void blackListSlowReceiver(CacheClientProxy clientProxy) {
    final CacheClientProxy proxy = clientProxy;
    if (proxy.getHARegionQueue() != null && proxy.getHARegionQueue().isClientSlowReciever()
        && !this.blackListedClients.contains(proxy.getProxyID())) {
      // log alert with client info.
      logger.warn(
          LocalizedMessage.create(LocalizedStrings.CacheClientNotifier_CLIENT_0_IS_A_SLOW_RECEIVER,
              new Object[] {proxy.getProxyID()}));
      addToBlacklistedClient(proxy.getProxyID());
      InternalDistributedSystem system = getCache().getInternalDistributedSystem();
      final DM dm = system.getDistributionManager();

      dm.getWaitingThreadPool().execute(new Runnable() {
        @Override
        public void run() {

          CacheDistributionAdvisor advisor =
              proxy.getHARegionQueue().getRegion().getCacheDistributionAdvisor();
          Set members = advisor.adviseCacheOp();

          // Send client blacklist message
          ClientBlacklistProcessor.sendBlacklistedClient(proxy.getProxyID(), dm, members);

          // close the proxy for slow receiver.
          proxy.close(false, false);
          removeClientProxy(proxy);

          if (PoolImpl.AFTER_QUEUE_DESTROY_MESSAGE_FLAG) {
            ClientServerObserver bo = ClientServerObserverHolder.getInstance();
            bo.afterQueueDestroyMessage();
          }

          // send remove from blacklist.
          RemoveClientFromBlacklistMessage rcm = new RemoveClientFromBlacklistMessage();
          rcm.setProxyID(proxy.getProxyID());
          dm.putOutgoing(rcm);
          blackListedClients.remove(proxy.getProxyID());
        }
      });
    }
  }

  /**
   * Initializes a {@code ClientUpdateMessage} from an operation and event
   *
   * @param operation The operation that occurred (e.g. AFTER_CREATE)
   * @param event The event containing the data to be updated
   * @return a {@code ClientUpdateMessage}
   */
  private ClientUpdateMessageImpl initializeMessage(EnumListenerEvent operation, CacheEvent event) {
    if (!supportsOperation(operation)) {
      throw new UnsupportedOperationException(
          LocalizedStrings.CacheClientNotifier_THE_CACHE_CLIENT_NOTIFIER_DOES_NOT_SUPPORT_OPERATIONS_OF_TYPE_0
              .toLocalizedString(operation));
    }

    Object keyOfInterest = null;
    final EventID eventIdentifier;
    ClientProxyMembershipID membershipID = null;
    boolean isNetLoad = false;
    Object callbackArgument;
    byte[] delta = null;
    VersionTag versionTag = null;

    if (event.getOperation().isEntry()) {
      EntryEventImpl entryEvent = (EntryEventImpl) event;
      versionTag = entryEvent.getVersionTag();
      delta = entryEvent.getDeltaBytes();
      callbackArgument = entryEvent.getRawCallbackArgument();
      if (entryEvent.isBridgeEvent()) {
        membershipID = entryEvent.getContext();
      }
      keyOfInterest = entryEvent.getKey();
      eventIdentifier = entryEvent.getEventId();
      isNetLoad = entryEvent.isNetLoad();
    } else {
      RegionEventImpl regionEvent = (RegionEventImpl) event;
      callbackArgument = regionEvent.getRawCallbackArgument();
      eventIdentifier = regionEvent.getEventId();
      if (event instanceof ClientRegionEventImpl) {
        ClientRegionEventImpl bridgeEvent = (ClientRegionEventImpl) event;
        membershipID = bridgeEvent.getContext();
      }
    }

    // NOTE: If delta is non-null, value MUST be in Object form of type Delta.
    ClientUpdateMessageImpl clientUpdateMsg =
        new ClientUpdateMessageImpl(operation, (LocalRegion) event.getRegion(), keyOfInterest, null,
            delta, (byte) 0x01, callbackArgument, membershipID, eventIdentifier, versionTag);

    if (event.getOperation().isEntry()) {
      EntryEventImpl entryEvent = (EntryEventImpl) event;
      // only need a value if notifyBySubscription is true
      entryEvent.exportNewValue(clientUpdateMsg);
    }

    if (isNetLoad) {
      clientUpdateMsg.setIsNetLoad(true);
    }

    return clientUpdateMsg;
  }

  /**
   * Returns whether the {@code CacheClientNotifier} supports the input operation.
   *
   * @param operation The operation that occurred (e.g. AFTER_CREATE)
   * @return whether the {@code CacheClientNotifier} supports the input operation
   */
  private boolean supportsOperation(EnumListenerEvent operation) {
    return operation == EnumListenerEvent.AFTER_CREATE
        || operation == EnumListenerEvent.AFTER_UPDATE
        || operation == EnumListenerEvent.AFTER_DESTROY
        || operation == EnumListenerEvent.AFTER_INVALIDATE
        || operation == EnumListenerEvent.AFTER_REGION_DESTROY
        || operation == EnumListenerEvent.AFTER_REGION_CLEAR
        || operation == EnumListenerEvent.AFTER_REGION_INVALIDATE;
  }

  /**
   * Registers client interest in the input region and key.
   *
   * @param regionName The name of the region of interest
   * @param keyOfInterest The name of the key of interest
   * @param membershipID clients ID
   * @param interestType type of registration
   * @param isDurable whether the registration persists when client goes away
   * @param sendUpdatesAsInvalidates client wants invalidation messages
   * @param manageEmptyRegions whether to book keep empty region information
   * @param regionDataPolicy (0=empty)
   */
  public void registerClientInterest(String regionName, Object keyOfInterest,
      ClientProxyMembershipID membershipID, int interestType, boolean isDurable,
      boolean sendUpdatesAsInvalidates, boolean manageEmptyRegions, int regionDataPolicy,
      boolean flushState) throws IOException, RegionDestroyedException {

    CacheClientProxy proxy = getClientProxy(membershipID, true);

    if (logger.isDebugEnabled()) {
      logger.debug(
          "CacheClientNotifier: Client {} registering interest in: {} -> {} (an instance of {})",
          proxy, regionName, keyOfInterest, keyOfInterest.getClass().getName());
    }

    if (proxy == null) {
      // client should see this and initiates failover
      throw new IOException(
          LocalizedStrings.CacheClientNotifier_CACHECLIENTPROXY_FOR_THIS_CLIENT_IS_NO_LONGER_ON_THE_SERVER_SO_REGISTERINTEREST_OPERATION_IS_UNSUCCESSFUL
              .toLocalizedString());
    }

    boolean done = false;
    try {
      proxy.registerClientInterest(regionName, keyOfInterest, interestType, isDurable,
          sendUpdatesAsInvalidates, flushState);

      if (manageEmptyRegions) {
        updateMapOfEmptyRegions(proxy.getRegionsWithEmptyDataPolicy(), regionName,
            regionDataPolicy);
      }

      done = true;
    } finally {
      if (!done) {
        proxy.unregisterClientInterest(regionName, keyOfInterest, interestType, false);
      }
    }
  }

  /**
   * Store region and delta relation
   * 
   * @param regionDataPolicy (0==empty)
   * @since GemFire 6.1
   */
  public void updateMapOfEmptyRegions(Map regionsWithEmptyDataPolicy, String regionName,
      int regionDataPolicy) {
    if (regionDataPolicy == 0) {
      if (!regionsWithEmptyDataPolicy.containsKey(regionName)) {
        regionsWithEmptyDataPolicy.put(regionName, 0);
      }
    }
  }

  /**
   * Unregisters client interest in the input region and key.
   *
   * @param regionName The name of the region of interest
   * @param keyOfInterest The name of the key of interest
   * @param isClosing Whether the caller is closing
   * @param membershipID The {@code ClientProxyMembershipID} of the client no longer interested in
   *        this {@code Region} and key
   */
  public void unregisterClientInterest(String regionName, Object keyOfInterest, int interestType,
      boolean isClosing, ClientProxyMembershipID membershipID, boolean keepalive) {
    if (logger.isDebugEnabled()) {
      logger.debug(
          "CacheClientNotifier: Client {} unregistering interest in: {} -> {} (an instance of {})",
          membershipID, regionName, keyOfInterest, keyOfInterest.getClass().getName());
    }
    CacheClientProxy proxy = getClientProxy(membershipID);
    if (proxy != null) {
      proxy.setKeepAlive(keepalive);
      proxy.unregisterClientInterest(regionName, keyOfInterest, interestType, isClosing);
    }
  }

  /**
   * Registers client interest in the input region and list of keys.
   *
   * @param regionName The name of the region of interest
   * @param keysOfInterest The list of keys of interest
   * @param membershipID The {@code ClientProxyMembershipID} of the client no longer interested in
   *        this {@code Region} and key
   */
  public void registerClientInterest(String regionName, List keysOfInterest,
      ClientProxyMembershipID membershipID, boolean isDurable, boolean sendUpdatesAsInvalidates,
      boolean manageEmptyRegions, int regionDataPolicy, boolean flushState)
      throws IOException, RegionDestroyedException {
    CacheClientProxy proxy = getClientProxy(membershipID, true);

    if (logger.isDebugEnabled()) {
      logger.debug("CacheClientNotifier: Client {} registering interest in: {} -> {}", proxy,
          regionName, keysOfInterest);
    }

    if (proxy == null) {
      throw new IOException(
          LocalizedStrings.CacheClientNotifier_CACHECLIENTPROXY_FOR_THIS_CLIENT_IS_NO_LONGER_ON_THE_SERVER_SO_REGISTERINTEREST_OPERATION_IS_UNSUCCESSFUL
              .toLocalizedString());
    }

    proxy.registerClientInterestList(regionName, keysOfInterest, isDurable,
        sendUpdatesAsInvalidates, flushState);

    if (manageEmptyRegions) {
      updateMapOfEmptyRegions(proxy.getRegionsWithEmptyDataPolicy(), regionName, regionDataPolicy);
    }
  }

  /**
   * Unregisters client interest in the input region and list of keys.
   *
   * @param regionName The name of the region of interest
   * @param keysOfInterest The list of keys of interest
   * @param isClosing Whether the caller is closing
   * @param membershipID The {@code ClientProxyMembershipID} of the client no longer interested in
   *        this {@code Region} and key
   */
  public void unregisterClientInterest(String regionName, List keysOfInterest, boolean isClosing,
      ClientProxyMembershipID membershipID, boolean keepalive) {
    if (logger.isDebugEnabled()) {
      logger.debug("CacheClientNotifier: Client {} unregistering interest in: {} -> {}",
          membershipID, regionName, keysOfInterest);
    }
    CacheClientProxy proxy = getClientProxy(membershipID);
    if (proxy != null) {
      proxy.setKeepAlive(keepalive);
      proxy.unregisterClientInterest(regionName, keysOfInterest, isClosing);
    }
  }

  /**
   * If the conflatable is an instance of HAEventWrapper, and if the corresponding entry is present
   * in the haContainer, set the reference to the clientUpdateMessage to null and putInProgress flag
   * to false. Also, if the ref count is zero, then remove the entry from the haContainer.
   * 
   * @since GemFire 5.7
   */
  private void checkAndRemoveFromClientMessagesRegion(Conflatable conflatable) {
    if (this.haContainer == null) {
      return;
    }

    if (conflatable instanceof HAEventWrapper) {
      HAEventWrapper wrapper = (HAEventWrapper) conflatable;
      if (!wrapper.getIsRefFromHAContainer()) {
        wrapper = (HAEventWrapper) this.haContainer.getKey(wrapper);
        if (wrapper != null && !wrapper.getPutInProgress()) {
          synchronized (wrapper) {
            if (wrapper.getReferenceCount() == 0L) {
              if (logger.isDebugEnabled()) {
                logger.debug("Removing event from haContainer: {}", wrapper);
              }
              this.haContainer.remove(wrapper);
            }
          }
        }
      } else {
        // This wrapper resides in haContainer.
        wrapper.setClientUpdateMessage(null);
        wrapper.setPutInProgress(false);
        synchronized (wrapper) {
          if (wrapper.getReferenceCount() == 0L) {
            if (logger.isDebugEnabled()) {
              logger.debug("Removing event from haContainer: {}", wrapper);
            }
            this.haContainer.remove(wrapper);
          }
        }
      }
    }
  }

  /**
   * Returns the {@code CacheClientProxy} associated to the membershipID *
   *
   * @return the {@code CacheClientProxy} associated to the membershipID
   */
  public CacheClientProxy getClientProxy(ClientProxyMembershipID membershipID) {
    return (CacheClientProxy) this.clientProxies.get(membershipID);
  }

  /**
   * Returns the CacheClientProxy associated to the membershipID. This looks at both proxies that
   * are initialized and those that are still in initialization mode.
   */
  public CacheClientProxy getClientProxy(ClientProxyMembershipID membershipID,
      boolean proxyInInitMode) {
    CacheClientProxy proxy = getClientProxy(membershipID);
    if (proxyInInitMode && proxy == null) {
      proxy = (CacheClientProxy) this.initClientProxies.get(membershipID);
    }
    return proxy;
  }

  /**
   * Returns the {@code CacheClientProxy} associated to the durableClientId
   * 
   * @return the {@code CacheClientProxy} associated to the durableClientId
   */
  public CacheClientProxy getClientProxy(String durableClientId) {
    return getClientProxy(durableClientId, false);
  }

  /**
   * Returns the {@code CacheClientProxy} associated to the durableClientId. This version of the
   * method can check for initializing proxies as well as fully initialized proxies.
   * 
   * @return the {@code CacheClientProxy} associated to the durableClientId
   */
  public CacheClientProxy getClientProxy(String durableClientId, boolean proxyInInitMode) {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    final boolean isTraceEnabled = logger.isTraceEnabled();

    if (isDebugEnabled) {
      logger.debug("CacheClientNotifier: Determining client for {}", durableClientId);
    }

    CacheClientProxy proxy = null;
    for (CacheClientProxy clientProxy : getClientProxies()) {
      if (isTraceEnabled) {
        logger.trace("CacheClientNotifier: Checking client {}", clientProxy);
      }
      if (clientProxy.getDurableId().equals(durableClientId)) {
        proxy = clientProxy;
        if (isDebugEnabled) {
          logger.debug("CacheClientNotifier: {} represents the durable client {}", proxy,
              durableClientId);
        }
        break;
      }
    }

    if (proxy == null && proxyInInitMode) {
      for (Object clientProxyObject : this.initClientProxies.values()) {
        CacheClientProxy clientProxy = (CacheClientProxy) clientProxyObject;
        if (isTraceEnabled) {
          logger.trace("CacheClientNotifier: Checking initializing client {}", clientProxy);
        }
        if (clientProxy.getDurableId().equals(durableClientId)) {
          proxy = clientProxy;
          if (isDebugEnabled) {
            logger.debug(
                "CacheClientNotifier: initializing client {} represents the durable client {}",
                proxy, durableClientId);
          }
          break;
        }
      }
    }
    return proxy;
  }

  /**
   * It will remove the clients connected to the passed acceptorId. If its the only server, shuts
   * down this instance.
   */
  protected synchronized void shutdown(long acceptorId) {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (isDebugEnabled) {
      logger.debug("At cache server shutdown time, the number of cache servers in the cache is {}",
          getCache().getCacheServers().size());
    }

    Iterator it = this.clientProxies.values().iterator();
    // Close all the client proxies
    while (it.hasNext()) {
      CacheClientProxy proxy = (CacheClientProxy) it.next();
      if (proxy.getAcceptorId() != acceptorId) {
        continue;
      }
      it.remove();
      try {
        if (isDebugEnabled) {
          logger.debug("CacheClientNotifier: Closing {}", proxy);
        }
        proxy.terminateDispatching(true);
      } catch (Exception ignore) {
        if (isDebugEnabled) {
          logger.debug("{}: Exception in closing down the CacheClientProxy", this, ignore);
        }
      }
    }

    if (noActiveServer() && getInstance() != null) {
      ccnSingleton = null;
      if (this.haContainer != null) {
        this.haContainer.cleanUp();
        if (isDebugEnabled) {
          logger.debug("haContainer ({}) is now cleaned up.", this.haContainer.getName());
        }
      }
      this.clearCompiledQueries();
      this.blackListedClients.clear();

      // cancel the ping task
      this.clientPingTask.cancel();

      // Close the statistics
      this.statistics.close();

      this.socketCloser.close();
    }
  }

  private boolean noActiveServer() {
    for (CacheServer server : getCache().getCacheServers()) {
      if (server.isRunning()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Adds a new {@code CacheClientProxy} to the list of known client proxies
   *
   * @param proxy The {@code CacheClientProxy} to add
   */
  void addClientProxy(CacheClientProxy proxy) {
    getCache(); // ensure cache reference is up to date so firstclient state is correct
    this.clientProxies.put(proxy.getProxyID(), proxy);
    // Remove this proxy from the init proxy list.
    removeClientInitProxy(proxy);
    this.connectionListener.queueAdded(proxy.getProxyID());
    if (proxy.clientConflation != HandShake.CONFLATION_ON) {
      // Delta not supported with conflation ON
      ClientHealthMonitor clientHealthMonitor = ClientHealthMonitor.getInstance();
      /*
       * #41788 - If the client connection init starts while cache/member is shutting down,
       * ClientHealthMonitor.getInstance() might return null.
       */
      if (clientHealthMonitor != null) {
        clientHealthMonitor.numOfClientsPerVersion.incrementAndGet(proxy.getVersion().ordinal());
      }
    }
    this.timedOutDurableClientProxies.remove(proxy.getProxyID());
  }

  private void addClientInitProxy(CacheClientProxy proxy) {
    this.initClientProxies.put(proxy.getProxyID(), proxy);
  }

  private void removeClientInitProxy(CacheClientProxy proxy) {
    this.initClientProxies.remove(proxy.getProxyID());
  }

  private boolean isProxyInInitializationMode(CacheClientProxy proxy) {
    return this.initClientProxies.containsKey(proxy.getProxyID());
  }

  /**
   * Returns (possibly stale) set of memberIds for all clients being actively notified by this
   * server.
   *
   * @return set of memberIds
   */
  public Set getActiveClients() {
    Set clients = new HashSet();
    for (CacheClientProxy proxy : getClientProxies()) {
      if (proxy.hasRegisteredInterested()) {
        ClientProxyMembershipID proxyID = proxy.getProxyID();
        clients.add(proxyID);
      }
    }
    return clients;
  }

  /**
   * Return (possibly stale) list of all clients and their status
   * 
   * @return Map, with CacheClientProxy as a key and CacheClientStatus as a value
   */
  public Map getAllClients() {
    Map clients = new HashMap();
    for (final Object o : this.clientProxies.values()) {
      CacheClientProxy proxy = (CacheClientProxy) o;
      ClientProxyMembershipID proxyID = proxy.getProxyID();
      clients.put(proxyID, new CacheClientStatus(proxyID));
    }
    return clients;
  }

  /**
   * Checks if there is any proxy present for the given durable client
   * 
   * @param durableId - id for the durable-client
   * @return - true if a proxy is present for the given durable client
   * 
   * @since GemFire 5.6
   */
  public boolean hasDurableClient(String durableId) {
    for (Object clientProxyObject : this.clientProxies.values()) {
      CacheClientProxy proxy = (CacheClientProxy) clientProxyObject;
      ClientProxyMembershipID proxyID = proxy.getProxyID();
      if (durableId.equals(proxyID.getDurableId())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if there is any proxy which is primary for the given durable client
   * 
   * @param durableId - id for the durable-client
   * @return - true if a primary proxy is present for the given durable client
   * 
   * @since GemFire 5.6
   */
  public boolean hasPrimaryForDurableClient(String durableId) {
    for (Object clientProxyObject : this.clientProxies.values()) {
      CacheClientProxy proxy = (CacheClientProxy) clientProxyObject;
      ClientProxyMembershipID proxyID = proxy.getProxyID();
      if (durableId.equals(proxyID.getDurableId())) {
        return proxy.isPrimary();
      }
    }
    return false;
  }

  /**
   * Returns (possibly stale) map of queue sizes for all clients notified by this server.
   *
   * @return map with CacheClientProxy as key, and Integer as a value
   */
  public Map getClientQueueSizes() {
    Map/* <ClientProxyMembershipID,Integer> */ queueSizes = new HashMap();
    for (Object clientProxyObject : this.clientProxies.values()) {
      CacheClientProxy proxy = (CacheClientProxy) clientProxyObject;
      queueSizes.put(proxy.getProxyID(), proxy.getQueueSize());
    }
    return queueSizes;
  }

  public int getDurableClientHAQueueSize(String durableClientId) {
    CacheClientProxy ccp = getClientProxy(durableClientId);
    if (ccp == null) {
      return -1;
    }
    return ccp.getQueueSizeStat();
  }

  // closes the cq and drains the queue
  public boolean closeClientCq(String durableClientId, String clientCQName) throws CqException {
    CacheClientProxy proxy = getClientProxy(durableClientId);
    // close and drain
    return proxy != null && proxy.closeClientCq(clientCQName);
  }

  /**
   * Removes an existing {@code CacheClientProxy} from the list of known client proxies
   *
   * @param proxy The {@code CacheClientProxy} to remove
   */
  void removeClientProxy(CacheClientProxy proxy) {
    ClientProxyMembershipID client = proxy.getProxyID();
    this.clientProxies.remove(client);
    this.connectionListener.queueRemoved();
    getCache().cleanupForClient(this, client);
    if (proxy.clientConflation != HandShake.CONFLATION_ON) {
      ClientHealthMonitor chm = ClientHealthMonitor.getInstance();
      if (chm != null) {
        chm.numOfClientsPerVersion.decrementAndGet(proxy.getVersion().ordinal());
      }
    }
  }

  void durableClientTimedOut(ClientProxyMembershipID client) {
    this.timedOutDurableClientProxies.add(client);
  }

  private boolean isTimedOut(ClientProxyMembershipID client) {
    return this.timedOutDurableClientProxies.contains(client);
  }

  /**
   * Returns an unmodifiable Collection of known {@code CacheClientProxy} instances. The collection
   * is not static so its contents may change.
   *
   * @return the collection of known {@code CacheClientProxy} instances
   */
  public Collection<CacheClientProxy> getClientProxies() {
    return Collections.unmodifiableCollection(this.clientProxies.values());
  }

  private void closeAllClientCqs(CacheClientProxy proxy) {
    CqService cqService = proxy.getCache().getCqService();
    if (cqService != null) {
      final boolean isDebugEnabled = logger.isDebugEnabled(); // LocalizedMessage.create(
      try {
        if (isDebugEnabled) {
          logger.debug("CacheClientNotifier: Closing client CQs: {}", proxy);
        }
        cqService.closeClientCqs(proxy.getProxyID());
      } catch (CqException e) {
        logger.warn(LocalizedMessage.create(
            LocalizedStrings.CacheClientNotifier_UNABLE_TO_CLOSE_CQS_FOR_THE_CLIENT__0,
            proxy.getProxyID()));
        if (isDebugEnabled) {
          logger.debug(e);
        }
      }
    }
  }

  /**
   * Shuts down durable client proxy
   */
  public boolean closeDurableClientProxy(String durableClientId) {
    CacheClientProxy ccp = getClientProxy(durableClientId);
    if (ccp == null) {
      return false;
    }
    // we can probably remove the isPaused check
    if (ccp.isPaused() && !ccp.isConnected()) {
      ccp.setKeepAlive(false);
      closeDeadProxies(Collections.singletonList(ccp), true);
      return true;
    } else {
      if (logger.isDebugEnabled()) {
        logger.debug("Cannot close running durable client: {}", durableClientId);
      }
      throw new IllegalStateException("Cannot close a running durable client : " + durableClientId);
    }
  }

  /**
   * Close dead {@code CacheClientProxy} instances
   *
   * @param deadProxies The list of {@code CacheClientProxy} instances to close
   */
  private void closeDeadProxies(List deadProxies, boolean stoppedNormally) {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    for (Object deadProxy : deadProxies) {
      CacheClientProxy proxy = (CacheClientProxy) deadProxy;
      if (isDebugEnabled) {
        logger.debug("CacheClientNotifier: Closing dead client: {}", proxy);
      }

      // Close the proxy
      boolean keepProxy = false;
      try {
        keepProxy = proxy.close(false, stoppedNormally);
      } catch (CancelException e) {
        throw e;
      } catch (Exception e) {
      }

      // Remove the proxy if necessary. It might not be necessary to remove the
      // proxy if it is durable.
      if (keepProxy) {
        logger.info(LocalizedMessage.create(
            LocalizedStrings.CacheClientNotifier_CACHECLIENTNOTIFIER_KEEPING_PROXY_FOR_DURABLE_CLIENT_NAMED_0_FOR_1_SECONDS_2,
            new Object[] {proxy.getDurableId(), proxy.getDurableTimeout(), proxy}));
      } else {
        closeAllClientCqs(proxy);
        if (isDebugEnabled) {
          logger.debug("CacheClientNotifier: Not keeping proxy for non-durable client: {}", proxy);
        }
        removeClientProxy(proxy);
      }
      proxy.notifyRemoval();
    } // for
  }

  /**
   * Registers a new {@code InterestRegistrationListener} with the set of
   * {@code InterestRegistrationListener}s.
   * 
   * @param listener The {@code InterestRegistrationListener} to register
   * 
   * @since GemFire 5.8Beta
   */
  public void registerInterestRegistrationListener(InterestRegistrationListener listener) {
    this.writableInterestRegistrationListeners.add(listener);
  }

  /**
   * Unregisters an existing {@code InterestRegistrationListener} from the set of
   * {@code InterestRegistrationListener}s.
   * 
   * @param listener The {@code InterestRegistrationListener} to unregister
   * 
   * @since GemFire 5.8Beta
   */
  public void unregisterInterestRegistrationListener(InterestRegistrationListener listener) {
    this.writableInterestRegistrationListeners.remove(listener);
  }

  /**
   * Returns a read-only collection of {@code InterestRegistrationListener}s registered with this
   * notifier.
   * 
   * @return a read-only collection of {@code InterestRegistrationListener}s registered with this
   *         notifier
   * 
   * @since GemFire 5.8Beta
   */
  public Set getInterestRegistrationListeners() {
    return this.readableInterestRegistrationListeners;
  }

  /**
   * 
   * @since GemFire 5.8Beta
   */
  boolean containsInterestRegistrationListeners() {
    return !this.writableInterestRegistrationListeners.isEmpty();
  }

  /**
   * @since GemFire 5.8Beta
   */
  void notifyInterestRegistrationListeners(InterestRegistrationEvent event) {
    for (Object writableInterestRegistrationListener : this.writableInterestRegistrationListeners) {
      InterestRegistrationListener listener =
          (InterestRegistrationListener) writableInterestRegistrationListener;
      if (event.isRegister()) {
        listener.afterRegisterInterest(event);
      } else {
        listener.afterUnregisterInterest(event);
      }
    }
  }

  /**
   * Test method used to determine the state of the CacheClientNotifier
   *
   * @return the statistics for the notifier
   */
  public CacheClientNotifierStats getStats() {
    return this.statistics;
  }

  /**
   * Returns this {@code CacheClientNotifier}'s {@code InternalCache}.
   * 
   * @return this {@code CacheClientNotifier}'s {@code InternalCache}
   */
  protected InternalCache getCache() { // TODO:SYNC: looks wrong
    if (this.cache != null && this.cache.isClosed()) {
      InternalCache cache = GemFireCacheImpl.getInstance();
      if (cache != null) {
        this.cache = cache;
        this.logWriter = cache.getInternalLogWriter();
        this.securityLogWriter = cache.getSecurityInternalLogWriter();
      }
    }
    return this.cache;
  }

  /**
   * Returns this {@code CacheClientNotifier}'s maximum message count.
   * 
   * @return this {@code CacheClientNotifier}'s maximum message count
   */
  protected int getMaximumMessageCount() {
    return this.maximumMessageCount;
  }

  /**
   * Returns this {@code CacheClientNotifier}'s message time-to-live.
   * 
   * @return this {@code CacheClientNotifier}'s message time-to-live
   */
  protected int getMessageTimeToLive() {
    return this.messageTimeToLive;
  }

  void handleInterestEvent(InterestRegistrationEvent event) {
    LocalRegion region = (LocalRegion) event.getRegion();
    region.handleInterestEvent(event);
  }

  void deliverInterestChange(ClientProxyMembershipID proxyID, ClientInterestMessageImpl message) {
    DM dm = getCache().getInternalDistributedSystem().getDistributionManager();
    ServerInterestRegistrationMessage.sendInterestChange(dm, proxyID, message);
  }

  CacheServerStats getAcceptorStats() {
    return this.acceptorStats;
  }

  SocketCloser getSocketCloser() {
    return this.socketCloser;
  }

  public void addCompiledQuery(DefaultQuery query) {
    if (this.compiledQueries.putIfAbsent(query.getQueryString(), query) == null) {
      // Added successfully.
      this.statistics.incCompiledQueryCount(1);
      if (logger.isDebugEnabled()) {
        logger.debug(
            "Added compiled query into ccn.compliedQueries list. Query: {}. Total compiled queries: {}",
            query.getQueryString(), this.statistics.getCompiledQueryCount());
      }
      // Start the clearIdleCompiledQueries thread.
      startCompiledQueryCleanupThread();
    }
  }

  public Query getCompiledQuery(String queryString) {
    return this.compiledQueries.get(queryString);
  }

  private void clearCompiledQueries() {
    if (!this.compiledQueries.isEmpty()) {
      this.statistics.incCompiledQueryCount(-this.compiledQueries.size());
      this.compiledQueries.clear();
      if (logger.isDebugEnabled()) {
        logger.debug(
            "Removed all compiled queries from ccn.compliedQueries list. Total compiled queries: {}",
            this.statistics.getCompiledQueryCount());
      }
    }
  }

  /**
   * This starts the cleanup thread that periodically (DefaultQuery.TEST_COMPILED_QUERY_CLEAR_TIME)
   * checks for the compiled queries that are not used and removes them.
   */
  private void startCompiledQueryCleanupThread() {
    if (this.isCompiledQueryCleanupThreadStarted) {
      return;
    }

    SystemTimer.SystemTimerTask task = new SystemTimer.SystemTimerTask() {
      @Override
      public void run2() {
        final boolean isDebugEnabled = logger.isDebugEnabled();
        for (Map.Entry<String, DefaultQuery> e : compiledQueries.entrySet()) {
          DefaultQuery q = e.getValue();
          // Check if the query last used flag.
          // If its true set it to false. If its false it means it is not used
          // from the its last checked.
          if (q.getLastUsed()) {
            q.setLastUsed(false);
          } else {
            if (compiledQueries.remove(e.getKey()) != null) {
              // If successfully removed decrement the counter.
              statistics.incCompiledQueryCount(-1);
              if (isDebugEnabled) {
                logger.debug("Removed compiled query from ccn.compliedQueries list. Query: "
                    + q.getQueryString() + ". Total compiled queries are : "
                    + statistics.getCompiledQueryCount());
              }
            }
          }
        }
      }
    };

    synchronized (this.lockIsCompiledQueryCleanupThreadStarted) {
      if (!this.isCompiledQueryCleanupThreadStarted) {
        long period = DefaultQuery.TEST_COMPILED_QUERY_CLEAR_TIME > 0
            ? DefaultQuery.TEST_COMPILED_QUERY_CLEAR_TIME : DefaultQuery.COMPILED_QUERY_CLEAR_TIME;
        this.cache.getCCPTimer().scheduleAtFixedRate(task, period, period);
      }
      this.isCompiledQueryCleanupThreadStarted = true;
    }
  }

  void scheduleClientPingTask() {
    this.clientPingTask = new SystemTimer.SystemTimerTask() {

      @Override
      public void run2() {
        // If there are no proxies, return
        if (clientProxies.isEmpty()) {
          return;
        }

        // Create ping message
        ClientMessage message = new ClientPingMessageImpl();

        // Determine clients to ping
        for (CacheClientProxy proxy : getClientProxies()) {
          logger.debug("Checking whether to ping {}", proxy);
          // Ping clients whose version is GE 6.6.2.2
          if (proxy.getVersion().compareTo(Version.GFE_6622) >= 0) {
            // Send the ping message directly to the client. Do not qo through
            // the queue. If the queue were used, the secondary connection would
            // not be pinged. Instead, pings would just build up in secondary
            // queue and never be sent. The counter is used to help scalability.
            // If normal messages are sent by the proxy, then the counter will
            // be reset and no pings will be sent.
            if (proxy.incrementAndGetPingCounter() >= CLIENT_PING_TASK_COUNTER) {
              logger.debug("Pinging {}", proxy);
              proxy.sendMessageDirectly(message);
              logger.debug("Done pinging {}", proxy);
            } else {
              logger.debug("Not pinging because not idle: {}", proxy);
            }
          } else {
            logger.debug("Ignoring because of version: {}", proxy);
          }
        }
      }
    };

    if (logger.isDebugEnabled()) {
      logger.debug("Scheduling client ping task with period={} ms", CLIENT_PING_TASK_PERIOD);
    }
    CacheClientNotifier.this.cache.getCCPTimer().scheduleAtFixedRate(this.clientPingTask,
        CLIENT_PING_TASK_PERIOD, CLIENT_PING_TASK_PERIOD);
  }

  /**
   * @return the haContainer
   */
  public Map getHaContainer() {
    return this.haContainer;
  }

  private void initHaContainer(List overflowAttributesList) {
    // lazily initialize haContainer in case this CCN instance was created by a gateway receiver
    if (overflowAttributesList != null
        && !HARegionQueue.HA_EVICTION_POLICY_NONE.equals(overflowAttributesList.get(0))) {
      this.haContainer = new HAContainerRegion(this.cache.getRegion(Region.SEPARATOR
          + CacheServerImpl.clientMessagesRegion(this.cache, (String) overflowAttributesList.get(0),
              (Integer) overflowAttributesList.get(1), (Integer) overflowAttributesList.get(2),
              (String) overflowAttributesList.get(3), (Boolean) overflowAttributesList.get(4))));
    } else {
      this.haContainer = new HAContainerMap(new ConcurrentHashMap());
    }
    assert this.haContainer != null;

    if (logger.isDebugEnabled()) {
      logger.debug("ha container ({}) has been created.", this.haContainer.getName());
    }
  }

  void addToBlacklistedClient(ClientProxyMembershipID proxyID) {
    this.blackListedClients.add(proxyID);
    // ensure that cache and distributed system state are current and open
    getCache();
    new ScheduledThreadPoolExecutor(1).schedule(new ExpireBlackListTask(proxyID), 120,
        TimeUnit.SECONDS);
  }

  Set getBlacklistedClient() {
    return this.blackListedClients;
  }

  /**
   * @param cache the cache to set
   */
  private void setCache(InternalCache cache) {
    this.cache = cache;
  }

  /**
   * Non-static inner class ExpireBlackListTask
   */
  private class ExpireBlackListTask extends PoolTask {
    private final ClientProxyMembershipID proxyID;

    ExpireBlackListTask(ClientProxyMembershipID proxyID) {
      this.proxyID = proxyID;
    }

    @Override
    public void run2() {
      if (blackListedClients.remove(this.proxyID)) {
        if (logger.isDebugEnabled()) {
          logger.debug("{} client is no longer blacklisted", this.proxyID);
        }
      }
    }
  }

}
