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
package org.apache.sling.jcr.contentloader.internal;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import java.lang.reflect.Field;
import java.util.Collections;

import javax.jcr.Session;

import org.apache.sling.jcr.contentloader.ContentReader;
import org.apache.sling.jcr.contentloader.internal.readers.JsonReader;
import org.apache.sling.jcr.contentloader.internal.readers.OrderedJsonReader;
import org.apache.sling.jcr.contentloader.internal.readers.XmlReader;
import org.apache.sling.jcr.contentloader.internal.readers.ZipReader;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.osgi.framework.Bundle;
import org.slf4j.LoggerFactory;

/**
 * Testing content loader waiting for required content reader
 */
public class SLING11203Test {

    protected org.slf4j.Logger logger = LoggerFactory.getLogger(getClass());

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private BundleContentLoaderListener bundleHelper;

    @Rule
    public TestRule watcher = new TestWatcher() {

        /* (non-Javadoc)
         * @see org.junit.rules.TestWatcher#starting(org.junit.runner.Description)
         */
        @Override
        protected void starting(Description description) {
            logger.info("Starting test: {}", description.getMethodName());
        }

        /* (non-Javadoc)
         * @see org.junit.rules.TestWatcher#finished(org.junit.runner.Description)
         */
        @Override
        protected void finished(Description description) {
           logger.info("Finished test: {}", description.getMethodName());
        }

    };

    @Before
    public void prepareContentLoader() throws Exception {
        // NOTE: initially only the default set of content readers are registered
        context.registerInjectActivateService(JsonReader.class);
        context.registerInjectActivateService(OrderedJsonReader.class);
        context.registerInjectActivateService(XmlReader.class);
        context.registerInjectActivateService(ZipReader.class);

        // whiteboard which holds readers
        context.registerInjectActivateService(new ContentReaderWhiteboard());

        // register the content loader service
        bundleHelper = context.registerInjectActivateService(new BundleContentLoaderListener());

    }

    @Test
    public void loadContentWithoutDirectiveExpectedContentReaderRegistered() throws Exception {
        loadContentWithDirective();

        // check node was not added during parsing the file
        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app"), nullValue());
        // check file was not loaded as non-parsed file
        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app.sling11203"), nullValue());
    }

    @Test
    public void loadContentWithDirectiveExpectedContentReaderRegisteredBeforeBundleLoaded() throws Exception {
        // register the content reader that we require before registering the bundle
        registerCustomContentReader();

        loadContentWithDirective();

        // check node was added during parsing the file
        assertThat("Included resource should have been imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
        // check file was not loaded as non-parsed file
        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app.sling11203"), nullValue());
    }

    @Test
    public void loadContentWithDirectiveExpectedContentReaderRegisteredAfterBundleLoaded() throws Exception {
        loadContentWithDirective();

        // check node was not added during parsing the file
        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app"), nullValue());
        // check file was not loaded as non-parsed file
        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app.sling11203"), nullValue());

        // register the content reader that we require
        registerCustomContentReader();

        // check node was added during parsing the file
        assertThat("Included resource should have been imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
        // check file was not loaded as non-parsed file
        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app.sling11203"), nullValue());
    }

    protected void registerCustomContentReader() {
        // register the content reader that we require after registering the bundle
        //   to trigger the retry
        context.registerService(ContentReader.class, new SLING11203XmlReader(), 
                Collections.singletonMap(ContentReader.PROPERTY_EXTENSIONS, "sling11203"));
    }

    protected void loadContentWithDirective() throws Exception {
        // dig the BundleContentLoader out of the component field so we get the
        //  same instance so the state for the retry logic is there
        Field privateBundleContentLoaderField = BundleContentLoaderListener.class.getDeclaredField("bundleContentLoader");
        privateBundleContentLoaderField.setAccessible(true);
        BundleContentLoader contentLoader = (BundleContentLoader)privateBundleContentLoaderField.get(bundleHelper);

        // requireImportProviders directive, so it should check if the specified 
        //  required content reader is available
        String initialContentHeader = "SLING-INF3/libs;path:=/libs;requireImportProviders:=sling11203";
        Bundle mockBundle = BundleContentLoaderTest.newBundleWithInitialContent(context, initialContentHeader);

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);
    }

    /**
     * A custom xml reader with a different file extension
     */
    public static class SLING11203XmlReader extends XmlReader {

        private SLING11203XmlReader() {
            super();
            activate();
        }

    }

}
