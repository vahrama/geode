/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geode.internal.security;

import static org.apache.geode.distributed.ConfigurationProperties.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Properties;

import org.apache.geode.security.PostProcessor;
import org.apache.geode.security.SecurityManager;
import org.apache.geode.test.junit.categories.UnitTest;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(UnitTest.class)
public class SecurityServiceFactoryTest {

  @Test
  public void getPostProcessor_null_throwsNPE() throws Exception {
    assertThatThrownBy(() -> SecurityServiceFactory.getPostProcessor(null, null)).isExactlyInstanceOf(NullPointerException.class);
  }

  @Test
  public void getPostProcessor_returnsPostProcessor() throws Exception {
    PostProcessor mockPostProcessor = mock(PostProcessor.class);

    assertThat(SecurityServiceFactory.getPostProcessor(mockPostProcessor, null)).isSameAs(mockPostProcessor);
  }

  @Test
  public void getPostProcessor_SecurityConfig_initsPostProcessor() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_POST_PROCESSOR, FakePostProcessor.class.getName());

    PostProcessor postProcessor = SecurityServiceFactory.getPostProcessor(null, securityConfig);

    assertThat(postProcessor).isInstanceOf(FakePostProcessor.class);

    FakePostProcessor fakePostProcessor = (FakePostProcessor) postProcessor;

    assertThat(fakePostProcessor.getInitInvocations()).isEqualTo(1);
    assertThat(fakePostProcessor.getSecurityProps()).isSameAs(securityConfig);
  }

  @Test
  public void getPostProcessor_prefersPostProcessorOverSecurityConfig() throws Exception {
    PostProcessor mockPostProcessor = mock(PostProcessor.class);
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_POST_PROCESSOR, FakePostProcessor.class.getName());

    assertThat(SecurityServiceFactory.getPostProcessor(mockPostProcessor, securityConfig)).isSameAs(mockPostProcessor);
  }

  @Test
  public void getSecurityManager_null_throwsNPE() throws Exception {
    assertThatThrownBy(() -> SecurityServiceFactory.getSecurityManager(null, null)).isExactlyInstanceOf(NullPointerException.class);
  }

  @Test
  public void getSecurityManager_returnsSecurityManager() throws Exception {
    SecurityManager mockSecurityManager = mock(SecurityManager.class);

    assertThat(SecurityServiceFactory.getSecurityManager(mockSecurityManager, null)).isSameAs(mockSecurityManager);
  }

  @Test
  public void getSecurityManager_SecurityConfig_initsSecurityManager() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_MANAGER, FakeSecurityManager.class.getName());

    SecurityManager securityManager = SecurityServiceFactory.getSecurityManager(null, securityConfig);

    assertThat(securityManager).isInstanceOf(FakeSecurityManager.class);

    FakeSecurityManager fakeSecurityManager = (FakeSecurityManager) securityManager;

    assertThat(fakeSecurityManager.getInitInvocations()).isEqualTo(1);
    assertThat(fakeSecurityManager.getSecurityProps()).isSameAs(securityConfig);
  }

  @Test
  public void getSecurityManager_prefersSecurityManagerOverSecurityConfig() throws Exception {
    SecurityManager mockSecurityManager = mock(SecurityManager.class);
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_MANAGER, FakePostProcessor.class.getName());

    assertThat(SecurityServiceFactory.getSecurityManager(mockSecurityManager, securityConfig)).isSameAs(mockSecurityManager);
  }

  @Test
  public void determineType_null_throwsNPE() throws Exception {
    assertThatThrownBy(() -> SecurityServiceFactory.determineType(null, null)).isExactlyInstanceOf(NullPointerException.class);
  }

  @Test
  public void determineType_shiro_returnsCUSTOM() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_SHIRO_INIT, "value");

    assertThat(SecurityServiceFactory.determineType(securityConfig, null)).isSameAs(SecurityServiceType.CUSTOM);
  }

  @Test
  public void determineType_securityManager_returnsENABLED() throws Exception {
    Properties securityConfig = new Properties();
    SecurityManager mockSecurityManager = mock(SecurityManager.class);

    assertThat(SecurityServiceFactory.determineType(securityConfig, mockSecurityManager)).isSameAs(SecurityServiceType.ENABLED);
  }

  @Test
  public void determineType_prefersCUSTOM() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_SHIRO_INIT, "value");
    securityConfig.setProperty(SECURITY_CLIENT_AUTHENTICATOR, "value");
    securityConfig.setProperty(SECURITY_PEER_AUTHENTICATOR, "value");
    SecurityManager mockSecurityManager = mock(SecurityManager.class);

    assertThat(SecurityServiceFactory.determineType(securityConfig, mockSecurityManager)).isSameAs(SecurityServiceType.CUSTOM);
  }

  @Test
  public void determineType_clientAuthenticator_returnsLEGACY() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_CLIENT_AUTHENTICATOR, "value");

    assertThat(SecurityServiceFactory.determineType(securityConfig, null)).isSameAs(SecurityServiceType.LEGACY);
  }

  @Test
  public void determineType_peerAuthenticator_returnsLEGACY() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_PEER_AUTHENTICATOR, "value");

    assertThat(SecurityServiceFactory.determineType(securityConfig, null)).isSameAs(SecurityServiceType.LEGACY);
  }

  @Test
  public void determineType_authenticators_returnsLEGACY() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_CLIENT_AUTHENTICATOR, "value");
    securityConfig.setProperty(SECURITY_PEER_AUTHENTICATOR, "value");

    assertThat(SecurityServiceFactory.determineType(securityConfig, null)).isSameAs(SecurityServiceType.LEGACY);
  }

  @Test
  public void determineType_empty_returnsDISABLED() throws Exception {
    Properties securityConfig = new Properties();

    assertThat(SecurityServiceFactory.determineType(securityConfig, null)).isSameAs(SecurityServiceType.DISABLED);
  }

  @Test
  @Ignore("Move to IntegrationTest with shiro config")
  public void create_shiro_createsCustomSecurityService() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_SHIRO_INIT, "value");

    assertThat(SecurityServiceFactory.create(securityConfig, null, null)).isInstanceOf(CustomSecurityService.class);
  }

  @Test
  public void create_clientAuthenticator_createsLegacySecurityService() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_CLIENT_AUTHENTICATOR, "value");

    assertThat(SecurityServiceFactory.create(securityConfig, null, null)).isInstanceOf(LegacySecurityService.class);
  }

  @Test
  public void create_peerAuthenticator_createsLegacySecurityService() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_PEER_AUTHENTICATOR, "value");

    assertThat(SecurityServiceFactory.create(securityConfig, null, null)).isInstanceOf(LegacySecurityService.class);
  }

  @Test
  public void create_authenticators_createsLegacySecurityService() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_CLIENT_AUTHENTICATOR, "value");
    securityConfig.setProperty(SECURITY_PEER_AUTHENTICATOR, "value");

    assertThat(SecurityServiceFactory.create(securityConfig, null, null)).isInstanceOf(LegacySecurityService.class);
  }

  @Test
  @Ignore("Move to IntegrationTest with shiro config")
  public void create_all_createsCustomSecurityService() throws Exception {
    Properties securityConfig = new Properties();
    securityConfig.setProperty(SECURITY_SHIRO_INIT, "value");
    securityConfig.setProperty(SECURITY_CLIENT_AUTHENTICATOR, "value");
    securityConfig.setProperty(SECURITY_PEER_AUTHENTICATOR, "value");

    SecurityManager mockSecurityManager = mock(SecurityManager.class);
    PostProcessor mockPostProcessor = mock(PostProcessor.class);

    assertThat(SecurityServiceFactory.create(securityConfig, mockSecurityManager, mockPostProcessor)).isInstanceOf(CustomSecurityService.class);
  }

  @Test
  public void create_none_createsDisabledSecurityService() throws Exception {
    Properties securityConfig = new Properties();

    assertThat(SecurityServiceFactory.create(securityConfig, null, null)).isInstanceOf(DisabledSecurityService.class);
  }
}