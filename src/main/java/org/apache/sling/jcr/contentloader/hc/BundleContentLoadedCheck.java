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
package org.apache.sling.jcr.contentloader.hc;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import java.util.Calendar;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.felix.hc.annotation.HealthCheckService;
import org.apache.felix.hc.api.FormattingResultLog;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.contentloader.PathEntry;
import org.apache.sling.jcr.contentloader.internal.BundleContentLoaderListener;
import org.apache.sling.jcr.contentloader.internal.BundleHelper;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Health check component that monitors the bundle content
 * loading progress for all of the deployed bundles.
 */
@HealthCheckService(name = BundleContentLoadedCheck.HC_NAME)
@Component(configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = BundleContentLoadedCheck.Config.class, factory = true)
public class BundleContentLoadedCheck implements HealthCheck {

    private static final Logger LOG = LoggerFactory.getLogger(BundleContentLoadedCheck.class);

    public static final String HC_NAME = "Bundle Content Loaded";
    public static final String HC_LABEL = "Health Check: " + HC_NAME;

    @ObjectClassDefinition(name = HC_LABEL, description = "Checks the configured path(s) against the given thresholds")
    public @interface Config {
        @AttributeDefinition(name = "Name", description = "Name of this health check")
        String hc_name() default HC_NAME; // NOSONAR

        @AttributeDefinition(
                name = "Tags",
                description =
                        "List of tags for this health check, used to select subsets of health checks for execution e.g. by a composite health check.")
        String[] hc_tags() default {}; // NOSONAR

        @AttributeDefinition(
                name = "Includes RegEx",
                description =
                        "RegEx to select all relevant bundles for this check. The RegEx is matched against the symbolic name of the bundle.")
        String includesRegex() default ".*";

        @AttributeDefinition(
                name = "Excludes RegEx",
                description =
                        "Optional RegEx to exclude bundles from this check (matched against symbolic name). Allows to exclude specific bundles from selected set as produced by 'Includes RegEx'.")
        String excludesRegex() default "";

        @AttributeDefinition(
                name = "CRITICAL for not loaded bundles",
                description =
                        "By default not loaded bundles produce warnings, if this is set to true not loaded bundles produce a CRITICAL result")
        boolean useCriticalForNotLoaded() default false;

        @AttributeDefinition
        String webconsole_configurationFactory_nameHint() default
                "Bundle content loaded includes: {includesRegex} excludes: {excludesRegex}"; // NOSONAR
    }

    private BundleContext bundleContext;
    private Pattern includesRegex;
    private Pattern excludesRegex;
    boolean useCriticalForNotLoaded;

    /**
     * SLING-10015 - To require a service user before becoming active
     */
    @Reference
    private ServiceUserMapped serviceUserMapped;

    /**
     * The JCR Repository we access to resolve resources
     */
    @Reference
    private SlingRepository repository;

    @Reference
    private BundleHelper bundleHelper;

    @Activate
    public void activate(BundleContext bundleContext, Config config) {
        this.bundleContext = bundleContext;
        this.includesRegex = Pattern.compile(config.includesRegex());
        String excludesRegex2 = config.excludesRegex();
        this.excludesRegex =
                (excludesRegex2 != null && !excludesRegex2.isEmpty()) ? Pattern.compile(excludesRegex2) : null;
        this.useCriticalForNotLoaded = config.useCriticalForNotLoaded();
        LOG.debug(
                "Activated bundle content loaded HC for includesRegex={} excludesRegex={}% useCriticalForNotLoaded={}",
                includesRegex, excludesRegex, useCriticalForNotLoaded);
    }

    @Override
    public Result execute() {
        FormattingResultLog log = new FormattingResultLog();

        Bundle[] bundles = this.bundleContext.getBundles();
        log.debug("Framwork has {} bundles in total", bundles.length);

        int countExcluded = 0;
        int relevantBundlesCount = 0;
        int notLoadedCount = 0;

        Session metadataSession = null;
        try {
            metadataSession = repository.loginService(null, null);

            for (Bundle bundle : bundles) {
                String bundleSymbolicName = bundle.getSymbolicName();
                boolean skip = false;
                if (!includesRegex.matcher(bundleSymbolicName).matches()) {
                    LOG.debug("Bundle {} not matched by {}", bundleSymbolicName, includesRegex);
                    skip = true;
                }

                if (excludesRegex != null
                        && excludesRegex.matcher(bundleSymbolicName).matches()) {
                    LOG.debug("Bundle {} excluded {}", bundleSymbolicName, excludesRegex);
                    countExcluded++;
                    skip = true;
                }

                if (skip) {
                    continue;
                }

                // check if bundle has initial content
                final Iterator<PathEntry> pathIter = PathEntry.getContentPaths(bundle);
                if (pathIter == null) {
                    log.debug("Bundle {} has no initial content", bundleSymbolicName);
                } else {
                    relevantBundlesCount++;

                    // check if the content has already been loaded
                    final Map<String, Object> bundleContentInfo =
                            bundleHelper.getBundleContentInfo(metadataSession, bundle, false);

                    // if we don't get an info, someone else is currently loading
                    if (bundleContentInfo == null) {
                        notLoadedCount++;
                        String msg = "Not loaded bundle {} {}";
                        Object[] msgObjs = new Object[] {bundle.getBundleId(), bundleSymbolicName};
                        LOG.debug(msg, msgObjs);
                        if (useCriticalForNotLoaded) {
                            log.critical(msg, msgObjs);
                        } else {
                            log.warn(msg, msgObjs);
                        }
                    } else {
                        try {
                            final boolean contentAlreadyLoaded = ((Boolean)
                                            bundleContentInfo.get(BundleContentLoaderListener.PROPERTY_CONTENT_LOADED))
                                    .booleanValue();
                            boolean isBundleUpdated = false;
                            Calendar lastLoadedAt = (Calendar)
                                    bundleContentInfo.get(BundleContentLoaderListener.PROPERTY_CONTENT_LOADED_AT);
                            if (lastLoadedAt != null && lastLoadedAt.getTimeInMillis() < bundle.getLastModified()) {
                                isBundleUpdated = true;
                            }
                            if (!isBundleUpdated && contentAlreadyLoaded) {
                                log.debug(
                                        "Content of bundle is already loaded {} {}.",
                                        bundle.getBundleId(),
                                        bundleSymbolicName);
                            } else {
                                notLoadedCount++;
                                String msg = "Not loaded bundle {} {}";
                                Object[] msgObjs = new Object[] {bundle.getBundleId(), bundleSymbolicName};
                                LOG.debug(msg, msgObjs);
                                if (useCriticalForNotLoaded) {
                                    log.critical(msg, msgObjs);
                                } else {
                                    log.warn(msg, msgObjs);
                                }
                            }
                        } finally {
                            bundleHelper.unlockBundleContentInfo(metadataSession, bundle, false, null);
                        }
                    }
                }
            }
        } catch (RepositoryException t) {
            String msg = "Unexpected error: " + t.getMessage();
            LOG.error(msg, t);
            log.critical(msg);
        } finally {
            if (metadataSession != null) {
                try {
                    metadataSession.logout();
                } catch (Exception t) {
                    LOG.error("Unable to log out of session: " + t.getMessage(), t);
                }
            }
        }

        String baseMsg = relevantBundlesCount + " bundles"
                + (!includesRegex.pattern().equals(".*") ? " for pattern " + includesRegex.pattern() : "");
        String excludedMsg = countExcluded > 0
                ? " (" + countExcluded + " excluded via pattern " + excludesRegex.pattern() + ")"
                : "";
        if (notLoadedCount > 0) {
            log.info("Found " + notLoadedCount + " not content loaded of " + baseMsg + excludedMsg);
        } else {
            log.info("All " + baseMsg + " are content loaded" + excludedMsg);
        }

        return new Result(log);
    }
}
