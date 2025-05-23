/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.core.security.jaas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.spi.core.security.jaas.JaasCallbackHandler;
import org.apache.activemq.artemis.spi.core.security.jaas.PropertiesLoader;
import org.apache.activemq.artemis.spi.core.security.jaas.PropertiesLoginModule;
import org.apache.activemq.artemis.tests.extensions.TargetTempDirFactory;
import org.apache.activemq.artemis.tests.util.ArtemisTestCase;
import org.apache.activemq.artemis.utils.ActiveMQThreadFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class PropertiesLoginModuleRaceConditionTest extends ArtemisTestCase {

   private static final String ROLES_FILE = "roles.properties";
   private static final String USERS_FILE = "users.properties";
   private static final String USERNAME = "first";
   private static final String PASSWORD = "secret";

   // Temp folder at ./target/tmp/<TestClassName>/<generated>
   @TempDir(factory = TargetTempDirFactory.class)
   public File temp;

   private Map<String, String> options;
   private BlockingQueue<Exception> errors;
   private ExecutorService pool;
   private CallbackHandler callback;

   private static class LoginTester implements Runnable {

      private final CountDownLatch finished;
      private final BlockingQueue<Exception> errors;
      private final Map<String, String> options;
      private final CountDownLatch start;
      private final CallbackHandler callback;

      LoginTester(CountDownLatch start,
                  CountDownLatch finished,
                  BlockingQueue<Exception> errors,
                  Map<String, String> options,
                  CallbackHandler callbackHandler) {
         this.finished = finished;
         this.errors = errors;
         this.options = options;
         this.start = start;
         this.callback = callbackHandler;
      }

      @Override
      public void run() {
         try {
            start.await();

            Subject subject = new Subject();
            PropertiesLoginModule module = new PropertiesLoginModule();
            module.initialize(subject, callback, new HashMap<>(), options);
            module.login();
            module.commit();
         } catch (Exception e) {
            errors.offer(e);
         } finally {
            finished.countDown();
         }
      }
   }

   @BeforeEach
   public void before() throws FileNotFoundException, IOException {
      createUsers();
      createGroups();

      options = new HashMap<>();
      options.put("reload", "true"); // Used to simplify reproduction of the
      // race condition
      options.put("org.apache.activemq.jaas.properties.user", USERS_FILE);
      options.put("org.apache.activemq.jaas.properties.role", ROLES_FILE);
      options.put("baseDir", temp.getAbsolutePath());

      errors = new ArrayBlockingQueue<>(processorCount());
      pool = Executors.newFixedThreadPool(processorCount(), ActiveMQThreadFactory.defaultThreadFactory(getClass().getName()));
      callback = new JaasCallbackHandler(USERNAME, PASSWORD, null);
   }

   @AfterEach
   public void after() throws InterruptedException {
      pool.shutdown();
      assertTrue(pool.awaitTermination(500, TimeUnit.SECONDS));
      PropertiesLoader.resetUsersAndGroupsCache();
   }

   @Test
   public void raceConditionInUsersAndGroupsLoading() throws Exception {
      boolean detected = false;

      // Brute force approach to increase the likelihood of the race condition occurring
      for (int i = 0; i < 25000; i++) {
         final CountDownLatch start = new CountDownLatch(1);
         final CountDownLatch finished = new CountDownLatch(processorCount());
         prepareLoginThreads(start, finished);

         // Releases every login thread simultaneously to increase our chances of
         // encountering the race condition
         start.countDown();

         finished.await();
         if (isRaceConditionDetected()) {
            detected = true;
            break;
         }
      }

      if (detected) {
         for (Throwable t : errors) {
            t.printStackTrace();
         }
      }

      assertFalse(detected, "Race condition detected. Consult logs for all stack traces. First error/message: " + errors.peek() + ". Assertion failed:");
   }

   private boolean isRaceConditionDetected() {
      return !errors.isEmpty();
   }

   private void prepareLoginThreads(final CountDownLatch start, final CountDownLatch finished) {
      for (int processor = 1; processor <= processorCount() * 2; processor++) {
         pool.submit(new LoginTester(start, finished, errors, options, callback));
      }
   }

   private int processorCount() {
      return Runtime.getRuntime().availableProcessors();
   }

   private void store(Properties from, File to) throws FileNotFoundException, IOException {
      try (FileOutputStream output = new FileOutputStream(to)) {
         from.store(output, "Generated by " + name);
      }
   }

   private void createGroups() throws FileNotFoundException, IOException {
      Properties groups = new Properties();
      for (int i = 0; i < 100; i++) {
         groups.put("group" + i, "first,second,third");
      }
      store(groups,  new File(temp, ROLES_FILE));
   }

   private void createUsers() throws FileNotFoundException, IOException {
      Properties users = new Properties();
      users.put(USERNAME, PASSWORD);
      users.put("second", PASSWORD);
      users.put("third", PASSWORD);
      store(users, new File(temp, USERS_FILE));
   }
}
