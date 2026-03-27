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

import javax.inject.Inject;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.ResultLog;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckSelector;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.testing.paxexam.SlingOptions;
import org.apache.sling.testing.paxexam.TestSupport;
import org.awaitility.Awaitility;
import org.junit.After;
import org.junit.Before;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.ModifiableCompositeOption;
import org.ops4j.pax.exam.options.extra.VMOption;
import org.ops4j.pax.tinybundles.TinyBundle;
import org.ops4j.pax.tinybundles.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.felix.hc.api.FormattingResultLog.msHumanReadable;
import static org.apache.sling.testing.paxexam.SlingOptions.jackson;
import static org.apache.sling.testing.paxexam.SlingOptions.paxLoggingApi;
import static org.apache.sling.testing.paxexam.SlingOptions.slingQuickstartOakTar;
import static org.apache.sling.testing.paxexam.SlingOptions.versionResolver;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.streamBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.when;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.factoryConfiguration;
import static org.ops4j.pax.tinybundles.TinyBundles.bndBuilder;

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

    ContentloaderTestSupport() {}

    @Inject
    private HealthCheckExecutor hcExecutor;

    @Override
    public ModifiableCompositeOption baseConfiguration() {
        final String vmOpt = System.getProperty("pax.vm.options");
        VMOption vmOption = null;
        if (vmOpt != null && !vmOpt.isEmpty()) {
            vmOption = new VMOption(vmOpt);
        }

        // SLING-13148 bump to a compatible version of commons-lang3
        //  NOTE: remove this when the versionResolver defaults to this version or later
        versionResolver.setVersionFromProject("org.apache.commons", "commons-lang3");

        // SLING-13148 switch to a version of oak compatible with java 17/21
        //  NOTE: remove this block when the versionResolver defaults to this version or later
        String oakVersion = "1.74.0";
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.jcr.oak.server", "1.4.4");
        versionResolver.setVersion("org.apache.jackrabbit", "oak-api", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-authorization-principalbased", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-blob", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-blob-plugins", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-commons", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-core", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-core-spi", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-jcr", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-lucene", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-query-spi", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-security-spi", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-segment-tar", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-store-composite", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-store-document", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-store-spi", oakVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "oak-jackrabbit-api", oakVersion);
        versionResolver.setVersion("commons-codec", "commons-codec", "1.21.0");
        versionResolver.setVersion("commons-io", "commons-io", "2.21.0");
        versionResolver.setVersion("org.apache.commons", "commons-text", "1.15.0");
        versionResolver.setVersion("org.apache.commons", "commons-lang3", "3.20.0");

        // SLING-13148 switch to a compatible version of jackson
        //  NOTE: remove this block when the versionResolver defaults to this version or later
        String jacksonVersion = "2.21.1";
        versionResolver.setVersion("com.fasterxml.jackson.core", "jackson-annotations", "2.21");
        versionResolver.setVersion("com.fasterxml.jackson.core", "jackson-core", jacksonVersion);
        versionResolver.setVersion("com.fasterxml.jackson.core", "jackson-databind", jacksonVersion);

        // SLING-13148 switch to a compatible version of jackrabbit
        //  NOTE: remove this block when the versionResolver defaults to this version or later
        String jackrabbitVersion = "2.22.0";
        versionResolver.setVersion("org.apache.jackrabbit", "jackrabbit-data", jackrabbitVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "jackrabbit-jcr-commons", jackrabbitVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "jackrabbit-jcr-rmi", jackrabbitVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "jackrabbit-spi", jackrabbitVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "jackrabbit-spi-commons", jackrabbitVersion);
        versionResolver.setVersion("org.apache.jackrabbit", "jackrabbit-webdav", jackrabbitVersion);

        // SLING-13148 switch to 3.x version of sling.api and related dependencies
        //  NOTE: remove this block when the versionResolver defaults to this version or later
        versionResolver.setVersionFromProject("org.apache.sling", "org.apache.sling.api");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.engine", "3.0.0");
        versionResolver.setVersion("org.apache.felix", "org.apache.felix.http.servlet-api", "6.1.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.resourceresolver", "2.0.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.auth.core", "2.0.0");
        versionResolver.setVersion("commons-fileupload", "commons-fileupload", "1.6.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.scripting.spi", "2.0.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.scripting.core", "3.0.0");
        versionResolver.setVersion("org.apache.sling", "org.apache.sling.servlets.resolver", "3.0.0");

        final Option contentloader = mavenBundle()
                .groupId("org.apache.sling")
                .artifactId("org.apache.sling.jcr.contentloader")
                .version(SlingOptions.versionResolver.getVersion(
                        "org.apache.sling", "org.apache.sling.jcr.contentloader"));
        return composite(
                        super.baseConfiguration(),
                        when(vmOption != null).useOptions(vmOption),
                        // SLING-13148 needed by oak 1.5+
                        //  NOTE: remove this block when the quickstart includes these
                        jackson(),
                        mavenBundle("org.apache.jackrabbit", "oak-shaded-guava").version(oakVersion),
                        mavenBundle("org.apache.commons", "commons-collections4", "4.5.0"),
                        mavenBundle("org.apache.commons", "commons-text"),
                        mavenBundle("org.apache.commons", "commons-math3", "3.6.1"),

                        // SLING-13148 add jakarta servlet wrappers required by org.apache.sling.engine 3.x
                        //  NOTE: remove this block when the quickstart includes this
                        mavenBundle()
                                .groupId("org.apache.felix")
                                .artifactId("org.apache.felix.http.wrappers")
                                .version("6.1.0"),

                        // SLING-13148 add jakarta json impl
                        //  NOTE: remove this block when the quickstart includes this
                        mavenBundle()
                                .groupId("org.apache.sling")
                                .artifactId("org.apache.sling.commons.johnzon")
                                .version("2.0.0"),
                        quickstart(),

                        // SLING-13148 newer version to provide the 2.x version of slf4j
                        paxLoggingApi(),
                        systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level")
                                .value("INFO"),

                        // SLING-9735 - add server user for the o.a.s.jcr.contentloader bundle
                        factoryConfiguration("org.apache.sling.jcr.repoinit.RepositoryInitializer")
                                .put("scripts", new String[] {"""
                                            create service user sling-jcr-content-loader
                                            set ACL for sling-jcr-content-loader
                                                allow   jcr:all    on /
                                            end
                                        """})
                                .asOption(),
                        factoryConfiguration("org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended")
                                .put(
                                        "user.mapping",
                                        new String[] {"org.apache.sling.jcr.contentloader=sling-jcr-content-loader"})
                                .asOption(),
                        // Sling JCR ContentLoader
                        testBundle("bundle.filename"),
                        factoryConfiguration("org.apache.sling.resource.presence.internal.ResourcePresenter")
                                .put("path", CONTENT_ROOT_PATH)
                                .asOption(),
                        // testing - add a user to use to login and verify the content loading has happened
                        factoryConfiguration("org.apache.sling.jcr.repoinit.RepositoryInitializer")
                                .put("scripts", new String[] {
                                    "create user " + CONTENT_LOADER_VERIFY_USER + " with password "
                                            + new String(CONTENT_LOADER_VERIFY_PWD) + "\n" + "\n"
                                            + "set ACL for content-loader-user\n"
                                            + "    allow   jcr:read              on /\n"
                                            + "    allow   jcr:readAccessControl on /\n"
                                            + "end"
                                })
                                .asOption(),
                        junitBundles(),
                        awaitility())
                .remove(contentloader)
                // SLING-13148 remove jcr-rmi which isn't used anymore
                //  NOTE: remove this block when the quickstart no longer includes bundle
                .remove(mavenBundle()
                        .groupId("org.apache.jackrabbit")
                        .artifactId("jackrabbit-jcr-rmi")
                        .version(versionResolver));
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
                mavenBundle().groupId("org.hamcrest").artifactId("hamcrest").version(SlingOptions.versionResolver));
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

        // SLING-13148 verify the content resource is present
        session.refresh(false);
        assertTrue(session.nodeExists(CONTENT_ROOT_PATH));
    }

    /**
     * Produce a human readable report of the health check results that is suitable for
     * debugging or writing to a log
     */
    protected String toHealthCheckResultInfo(final HealthCheckExecutionResult exResult, final boolean debug)
            throws IOException {
        String value = null;
        try (StringWriter resultWriter = new StringWriter();
                BufferedWriter writer = new BufferedWriter(resultWriter)) {
            final Result result = exResult.getHealthCheckResult();

            writer.append('"')
                    .append(exResult.getHealthCheckMetadata().getTitle())
                    .append('"');
            writer.append(" result is: ").append(result.getStatus().toString());
            writer.newLine();
            writer.append("   Finished: ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(exResult.getFinishedAt()) + " after "
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
            bundle.addResource(pathInBundle, is);
        }
    }

    protected Option buildInitialContentBundle(final String header, final Map<String, Collection<String>> content)
            throws IOException {
        final TinyBundle bundle = TinyBundles.bundle();
        bundle.setHeader(Constants.BUNDLE_SYMBOLICNAME, BUNDLE_SYMBOLICNAME);
        bundle.setHeader(SLING_INITIAL_CONTENT_HEADER, header);
        for (final Map.Entry<String, Collection<String>> entry : content.entrySet()) {
            for (String resourcePath : entry.getValue()) {
                addContent(bundle, entry.getKey(), resourcePath);
            }
        }
        return streamBundle(bundle.build(bndBuilder())).start();
    }

    protected Bundle findBundle(final String symbolicName) {
        for (final Bundle bundle : bundleContext.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        return null;
    }

    protected void assertProperty(final Session session, final String path, final String expected)
            throws RepositoryException {
        assertTrue("Expecting property " + path, session.itemExists(path));
        final String actual = session.getProperty(path).getString();
        assertEquals("Expecting correct value at " + path, expected, actual);
    }
}
