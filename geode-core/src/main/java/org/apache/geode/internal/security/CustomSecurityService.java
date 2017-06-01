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
package org.apache.geode.internal.security;

import java.util.Properties;
import java.util.concurrent.Callable;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadState;

import org.apache.geode.management.internal.security.ResourceOperation;
import org.apache.geode.security.PostProcessor;
import org.apache.geode.security.ResourcePermission;
import org.apache.geode.security.SecurityManager;

public class CustomSecurityService implements SecurityService {

  CustomSecurityService() {
    // nothing
  }

  @Override
  public void initSecurity(final Properties securityProps) {

  }

  @Override
  public void setSecurityManager(final SecurityManager securityManager) {

  }

  @Override
  public void setPostProcessor(final PostProcessor postProcessor) {

  }

  @Override
  public ThreadState bindSubject(final Subject subject) {
    return null;
  }

  @Override
  public Subject getSubject() {
    return null;
  }

  @Override
  public Subject login(final Properties credentials) {
    return null;
  }

  @Override
  public void logout() {

  }

  @Override
  public Callable associateWith(final Callable callable) {
    return null;
  }

  @Override
  public void authorize(final ResourceOperation resourceOperation) {

  }

  @Override
  public void authorizeClusterManage() {

  }

  @Override
  public void authorizeClusterWrite() {

  }

  @Override
  public void authorizeClusterRead() {

  }

  @Override
  public void authorizeDataManage() {

  }

  @Override
  public void authorizeDataWrite() {

  }

  @Override
  public void authorizeDataRead() {

  }

  @Override
  public void authorizeRegionManage(final String regionName) {

  }

  @Override
  public void authorizeRegionManage(final String regionName, final String key) {

  }

  @Override
  public void authorizeRegionWrite(final String regionName) {

  }

  @Override
  public void authorizeRegionWrite(final String regionName, final String key) {

  }

  @Override
  public void authorizeRegionRead(final String regionName) {

  }

  @Override
  public void authorizeRegionRead(final String regionName, final String key) {

  }

  @Override
  public void authorize(final String resource, final String operation) {

  }

  @Override
  public void authorize(final String resource, final String operation, final String regionName) {

  }

  @Override
  public void authorize(final String resource, final String operation, final String regionName,
      final String key) {

  }

  @Override
  public void authorize(final ResourcePermission context) {

  }

  @Override
  public void close() {

  }

  @Override
  public boolean needPostProcess() {
    return false;
  }

  @Override
  public Object postProcess(final String regionPath, final Object key, final Object value,
      final boolean valueIsSerialized) {
    return null;
  }

  @Override
  public Object postProcess(final Object principal, final String regionPath, final Object key,
      final Object value, final boolean valueIsSerialized) {
    return null;
  }

  @Override
  public boolean isClientSecurityRequired() {
    return false;
  }

  @Override
  public boolean isIntegratedSecurity() {
    return true;
  }

  @Override
  public boolean isPeerSecurityRequired() {
    return false;
  }

  @Override
  public SecurityManager getSecurityManager() {
    return null;
  }

  @Override
  public PostProcessor getPostProcessor() {
    return null;
  }
}
