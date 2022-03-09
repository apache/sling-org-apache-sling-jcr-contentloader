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
import java.util.Map;

import javax.jcr.Session;

import org.apache.sling.jcr.contentloader.internal.readers.XmlReader;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Rule;
import org.junit.Test;
import org.osgi.framework.Bundle;

/**
 * Testing content loader waiting for required content reader
 */
public class SLING11189Test {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private BundleContentLoaderListener bundleHelper;

    protected void prepareContentLoader(Map<String, Object> bundleContentLoaderConfig) throws Exception {
        // NOTE: initially no content readers are registered

        // whiteboard which holds readers
        context.registerInjectActivateService(new ContentReaderWhiteboard());

        // register the content loader service
        bundleHelper = context.registerInjectActivateService(new BundleContentLoaderListener(), bundleContentLoaderConfig);

    }

    @Test
    public void loadContentWithoutDefaultExpectedContentReaderRegistered() throws Exception {
        loadContentWithoutDirectiveAndWithDefault();

        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app"), nullValue());
    }

    @Test
    public void loadContentWithoutDirectiveExpectedContentReaderRegistered() throws Exception {
        loadContentWithDirectiveAndWithoutDefault();

        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app"), nullValue());
    }

    @Test
    public void loadContentWithDefaultExpectedContentReaderRegisteredBeforeBundleLoaded() throws Exception {
        // register the content reader that we require before registering the bundle
        context.registerInjectActivateService(new XmlReader());

        loadContentWithoutDirectiveAndWithDefault();

        assertThat("Included resource should have been imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
    }

    @Test
    public void loadContentWithDirectiveExpectedContentReaderRegisteredBeforeBundleLoaded() throws Exception {
        // register the content reader that we require before registering the bundle
        context.registerInjectActivateService(new XmlReader());

        loadContentWithDirectiveAndWithoutDefault();

        assertThat("Included resource should have been imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
    }

    @Test
    public void loadContentWithDefaultExpectedContentReaderRegisteredAfterBundleLoaded() throws Exception {
        loadContentWithoutDirectiveAndWithDefault();

        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app"), nullValue());

        // register the content reader that we require after registering the bundle
        //   to trigger the retry
        context.registerInjectActivateService(new XmlReader());

        assertThat("Included resource should have been imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
    }

    @Test
    public void loadContentWithDirectiveExpectedContentReaderRegisteredAfterBundleLoaded() throws Exception {
        loadContentWithDirectiveAndWithoutDefault();

        assertThat("Included resource should not have been imported", context.resourceResolver().getResource("/libs/app"), nullValue());

        // register the content reader that we require after registering the bundle
        //   to trigger the retry
        context.registerInjectActivateService(new XmlReader());

        assertThat("Included resource should have been imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
    }

    protected void loadContentWithoutDirectiveAndWithDefault() throws Exception {
        // prepare the content loader with the default configuration
        prepareContentLoader(null);

        // dig the BundleContentLoader out of the component field so we get the
        //  same instance so the state for the retry logic is there
        Field privateBundleContentLoaderField = BundleContentLoaderListener.class.getDeclaredField("bundleContentLoader");
        privateBundleContentLoaderField.setAccessible(true);
        BundleContentLoader contentLoader = (BundleContentLoader)privateBundleContentLoaderField.get(bundleHelper);

        // no requireImportProviders directive, so it should fallback to the
        //  the defaultRequireImportProviders configuration to check if the 
        //  required content reader is available
        String initialContentHeader = "SLING-INF/libs;path:=/libs";
        Bundle mockBundle = BundleContentLoaderTest.newBundleWithInitialContent(context, initialContentHeader);

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);
    }

    protected void loadContentWithDirectiveAndWithoutDefault() throws Exception {
        // prepare the content loader with customized configuration
        //  to clear out the defaultRequireImportProviders values 
        prepareContentLoader(Collections.singletonMap("defaultRequireImportProviders", new String[0]));

        // dig the BundleContentLoader out of the component field so we get the
        //  same instance so the state for the retry logic is there
        Field privateBundleContentLoaderField = BundleContentLoaderListener.class.getDeclaredField("bundleContentLoader");
        privateBundleContentLoaderField.setAccessible(true);
        BundleContentLoader contentLoader = (BundleContentLoader)privateBundleContentLoaderField.get(bundleHelper);

        // requireImportProviders directive, so it should check if the specified 
        //  required content reader is available
        String initialContentHeader = "SLING-INF/libs;path:=/libs;requireImportProviders:=xml";
        Bundle mockBundle = BundleContentLoaderTest.newBundleWithInitialContent(context, initialContentHeader);

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);
    }

}
