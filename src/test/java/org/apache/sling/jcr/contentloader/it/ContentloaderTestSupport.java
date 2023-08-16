/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.jcr.contentloader.it;

import static org.apache.felix.hc.api.FormattingResultLog.msHumanReadable;
import static org.apache.sling.testing.paxexam.SlingOptions.slingQuickstartOakTar;
import static org.apache.sling.testing.paxexam.SlingOptions.slingResourcePresence;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.streamBundle;
import static org.ops4j.pax.exam.CoreOptions.when;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.factoryConfiguration;
import static org.ops4j.pax.tinybundles.core.TinyBundles.withBnd;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.ResultLog;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckSelector;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.resource.presence.ResourcePresence;
import org.apache.sling.testing.paxexam.SlingOptions;
import org.apache.sling.testing.paxexam.TestSupport;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.ModifiableCompositeOption;
import org.ops4j.pax.exam.options.extra.VMOption;
import org.ops4j.pax.exam.util.Filter;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Multimap;

public abstract class ContentloaderTestSupport extends TestSupport {

    protected static final String CONTENT_LOADER_VERIFY_USER = "content-loader-user";
    protected static final char[] CONTENT_LOADER_VERIFY_PWD = "testing".toCharArray();

    protected static final String TAG_TESTING_CONTENT_LOADING = "testing-content-loading";

    @Inject
    protected BundleContext bundleContext;

    @Inject
    protected SlingRepository repository;

    protected Session session;

    protected static final String SLING_INITIAL_CONTENT_HEADER = "Sling-Initial-Content";

    protected static final String BUNDLE_SYMBOLICNAME = "TEST-CONTENT-BUNDLE";

    protected static final String DEFAULT_PATH_IN_BUNDLE = "test-initial-content";

    protected static final String CONTENT_ROOT_PATH = "/test-content/" + BUNDLE_SYMBOLICNAME;

    private final Logger logger = LoggerFactory.getLogger(ContentloaderTestSupport.class);

    ContentloaderTestSupport() {
    }

    @Inject
    @Filter(value = "(path=" + CONTENT_ROOT_PATH + ")")
    private ResourcePresence resourcePresence;

    @Inject
    private HealthCheckExecutor hcExecutor;
    
    public ModifiableCompositeOption baseConfiguration() {
        final String vmOpt = System.getProperty("pax.vm.options");
        VMOption vmOption = null;
        if (vmOpt != null && !vmOpt.isEmpty()) {
            vmOption = new VMOption(vmOpt);
        }

        final Option contentloader = mavenBundle().groupId("org.apache.sling").artifactId("org.apache.sling.jcr.contentloader").version(SlingOptions.versionResolver.getVersion("org.apache.sling", "org.apache.sling.jcr.contentloader"));
        return composite(
            super.baseConfiguration(),
            when(vmOption != null).useOptions(vmOption),
            mavenBundle().groupId("org.glassfish").artifactId("jakarta.json").version("2.0.1"),
            quickstart(),
            // SLING-9735 - add server user for the o.a.s.jcr.contentloader bundle
            factoryConfiguration("org.apache.sling.jcr.repoinit.RepositoryInitializer")
                .put("scripts", new String[] {
                        "create service user sling-jcr-content-loader\n" +
                        "\n" +
                        "set ACL for sling-jcr-content-loader\n" +
                        "    allow   jcr:all    on /\n" +
                        "end"})
                .asOption(),
            factoryConfiguration("org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended")
                .put("user.mapping", new String[]{"org.apache.sling.jcr.contentloader=sling-jcr-content-loader"})
                .asOption(),
            // Sling JCR ContentLoader
            testBundle("bundle.filename"),
            factoryConfiguration("org.apache.sling.resource.presence.internal.ResourcePresenter")
                .put("path", CONTENT_ROOT_PATH)
                .asOption(),
            // testing - add a user to use to login and verify the content loading has happened
            factoryConfiguration("org.apache.sling.jcr.repoinit.RepositoryInitializer")
                .put("scripts", new String[] {
                        "create user " + CONTENT_LOADER_VERIFY_USER + " with password " + new String(CONTENT_LOADER_VERIFY_PWD) +"\n" +
                        "\n" +
                        "set ACL for content-loader-user\n" +
                        "    allow   jcr:read              on /\n" +
                        "    allow   jcr:readAccessControl on /\n" +
                        "end"})
                .asOption(),
            slingResourcePresence(),
            junitBundles(),
            awaitility()
        ).remove(
            contentloader
        );
    }

    protected ModifiableCompositeOption quickstart() {
        final int httpPort = findFreePort();
        final String workingDirectory = workingDirectory();
        return slingQuickstartOakTar(workingDirectory, httpPort);
    }

    /**
     * Replacement for {@link SlingOptions#awaitility()} to utilize a newer version of awaitility
     * <p>
     * NOTE: may remove this at a later date and go back to {@link SlingOptions#awaitility()} whenever
     * {@link org.apache.sling.testing.paxexam.SlingVersionResolver} provides these versions or later
     */
    public static ModifiableCompositeOption awaitility() {
        return composite(
                mavenBundle().groupId("org.awaitility").artifactId("awaitility").versionAsInProject(),
                mavenBundle().groupId("org.hamcrest").artifactId("hamcrest").version(SlingOptions.versionResolver)
        );
    }


    @Before
    public void setup() throws Exception {
        session = repository.login(new SimpleCredentials(CONTENT_LOADER_VERIFY_USER, CONTENT_LOADER_VERIFY_PWD));
    }

    @After
    public void teardown() {
        session.logout();
    }

    /**
     * Wait for the bundle content loading to be completed.
     * Timeout is 2 minutes with 5 second iteration delay.
     */
    protected void waitForContentLoaded() throws Exception {
        waitForContentLoaded(TimeUnit.MINUTES.toMillis(2), TimeUnit.SECONDS.toMillis(5));
    }
    /**
     * Wait for the bundle content loading to be completed
     * 
     * @param timeoutMsec the max time to wait for the content to be loaded
     * @param nextIterationDelay the sleep time between the check attempts
     */
    protected void waitForContentLoaded(long timeoutMsec, long nextIterationDelay) throws Exception {
        Awaitility.await("waitForContentLoaded")
            .atMost(Duration.ofMillis(timeoutMsec))
            .pollInterval(Duration.ofMillis(nextIterationDelay))
            .ignoreExceptions()
            .until(() -> {
                logger.info("Performing content-loaded health check");
                HealthCheckSelector hcs = HealthCheckSelector.tags(TAG_TESTING_CONTENT_LOADING);
                List<HealthCheckExecutionResult> results = hcExecutor.execute(hcs);
                logger.info("content-loaded health check got {} results", results.size());
                assertFalse(results.isEmpty());
                for (final HealthCheckExecutionResult exR : results) {
                    final Result r = exR.getHealthCheckResult();
                    logger.info("content-loaded health check: {}", toHealthCheckResultInfo(exR, false));
                    assertTrue(r.isOk());
                }
                return true;
            });
    }

    /**
     * Produce a human readable report of the health check results that is suitable for
     * debugging or writing to a log
     */
    protected String toHealthCheckResultInfo(final HealthCheckExecutionResult exResult, final boolean debug)  throws IOException {
        String value = null;
        try (StringWriter resultWriter = new StringWriter(); BufferedWriter writer = new BufferedWriter(resultWriter)) {
            final Result result = exResult.getHealthCheckResult();

            writer.append('"').append(exResult.getHealthCheckMetadata().getTitle()).append('"');
            writer.append(" result is: ").append(result.getStatus().toString());
            writer.newLine();
            writer.append("   Finished: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(exResult.getFinishedAt()) + " after "
                    + msHumanReadable(exResult.getElapsedTimeInMs()));

            for (final ResultLog.Entry e : result) {
                if (!debug && e.isDebug()) {
                    continue;
                }
                writer.newLine();
                writer.append("   ");
                writer.append(e.getStatus().toString());
                writer.append(' ');
                writer.append(e.getMessage());
                if (e.getException() != null) {
                    writer.append(" ");
                    writer.append(e.getException().toString());
                }
            }
            writer.flush();
            value = resultWriter.toString();
        }
        return value;
    }
    
    /**
     * Add content to our test bundle
     */
    protected void addContent(final TinyBundle bundle, String pathInBundle, String resourcePath) throws IOException {
        pathInBundle += "/" + resourcePath;
        resourcePath = "/initial-content/" + resourcePath;
        try (final InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull("Expecting resource to be found:" + resourcePath, is);
            logger.info("Adding resource to bundle, path={}, resource={}", pathInBundle, resourcePath);
            bundle.add(pathInBundle, is);
        }
    }

    protected Option buildInitialContentBundle(final String header, final Multimap<String, String> content) throws IOException {
        final TinyBundle bundle = TinyBundles.bundle();
        bundle.set(Constants.BUNDLE_SYMBOLICNAME, BUNDLE_SYMBOLICNAME);
        bundle.set(SLING_INITIAL_CONTENT_HEADER, header);
        for (final Map.Entry<String, String> entry : content.entries()) {
            addContent(bundle, entry.getKey(), entry.getValue());
        }
        return streamBundle(
            bundle.build(withBnd())
        ).start();
    }

    protected Bundle findBundle(final String symbolicName) {
        for (final Bundle bundle : bundleContext.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        return null;
    }

    protected void assertProperty(final Session session, final String path, final String expected) throws RepositoryException {
        assertTrue("Expecting property " + path, session.itemExists(path));
        final String actual = session.getProperty(path).getString();
        assertEquals("Expecting correct value at " + path, expected, actual);
    }

}
