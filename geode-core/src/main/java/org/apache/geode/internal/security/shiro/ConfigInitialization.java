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
package org.apache.geode.internal.security.shiro;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.config.Ini.Section;
import org.apache.shiro.config.IniSecurityManagerFactory;

public class ConfigInitialization {

  private final String shiroConfig;

  public ConfigInitialization(String shiroConfig) {
    this.shiroConfig = shiroConfig;
  }

  public void initialize() {
    IniSecurityManagerFactory factory =
        new IniSecurityManagerFactory("classpath:" + this.shiroConfig);

    // we will need to make sure that shiro uses a case sensitive permission resolver
    Section main = factory.getIni().addSection("main");
    main.put("geodePermissionResolver",
        "org.apache.geode.internal.security.shiro.GeodePermissionResolver");
    if (!main.containsKey("iniRealm.permissionResolver")) {
      main.put("iniRealm.permissionResolver", "$geodePermissionResolver");
    }

    org.apache.shiro.mgt.SecurityManager securityManager = factory.getInstance();
    SecurityUtils.setSecurityManager(securityManager);
  }

}
