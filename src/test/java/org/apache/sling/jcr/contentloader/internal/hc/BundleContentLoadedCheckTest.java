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
package org.apache.sling.jcr.contentloader.internal.hc;

import javax.jcr.Session;

import java.lang.annotation.Annotation;

import org.apache.felix.hc.api.Result;
import org.apache.sling.jcr.contentloader.hc.BundleContentLoadedCheck;
import org.apache.sling.jcr.contentloader.hc.BundleContentLoadedCheck.Config;
import org.apache.sling.jcr.contentloader.internal.BundleContentLoader;
import org.apache.sling.jcr.contentloader.internal.BundleContentLoaderListener;
import org.apache.sling.jcr.contentloader.internal.BundleContentLoaderTest;
import org.apache.sling.jcr.contentloader.internal.BundleHelper;
import org.apache.sling.jcr.contentloader.internal.ContentReaderWhiteboard;
import org.apache.sling.jcr.contentloader.internal.readers.JsonReader;
import org.apache.sling.jcr.contentloader.internal.readers.OrderedJsonReader;
import org.apache.sling.jcr.contentloader.internal.readers.XmlReader;
import org.apache.sling.jcr.contentloader.internal.readers.ZipReader;
import org.apache.sling.testing.mock.osgi.MockBundle;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BundleContentLoadedCheckTest {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private MockBundle bundle;
    private Mockery mock = new Mockery();
    private BundleContentLoader contentLoader;
    private BundleContentLoadedCheck check;

    @Before
    public void setup() {
        bundle = BundleContentLoaderTest.newBundleWithInitialContent(context, "SLING-INF/libs/app;path:=/libs/app");

        // prepare content readers
        context.registerInjectActivateService(JsonReader.class);
        context.registerInjectActivateService(OrderedJsonReader.class);
        context.registerInjectActivateService(XmlReader.class);
        context.registerInjectActivateService(ZipReader.class);

        // whiteboard which holds readers
        context.registerInjectActivateService(new ContentReaderWhiteboard());

        // register the content loader service
        BundleHelper bundleHelper = context.registerInjectActivateService(new BundleContentLoaderListener());

        ContentReaderWhiteboard whiteboard = context.getService(ContentReaderWhiteboard.class);

        contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);

        BundleContext bundleContext = mock.mock(BundleContext.class);
        mock.checking(new Expectations() {
            {
                oneOf(bundleContext).getBundles();
                will(returnValue(new Bundle[] {bundle}));
            }
        });
        check = context.registerInjectActivateService(new BundleContentLoadedCheck());
        check.activate(bundleContext, new Config() {

            @Override
            public Class<? extends Annotation> annotationType() {
                return null;
            }

            @Override
            public String hc_name() {
                return "Unity";
            }

            @Override
            public String[] hc_tags() {
                return new String[] {"test"};
            }

            @Override
            public String includesRegex() {
                return ".*";
            }

            @Override
            public String excludesRegex() {
                return "";
            }

            @Override
            public boolean useCriticalForNotLoaded() {
                return false;
            }

            @Override
            public String webconsole_configurationFactory_nameHint() {
                return null;
            }
        });
    }

    @Test
    public void testNotInstalled() {
        Result result = check.execute();
        assertFalse(result.isOk());
    }

    @Test
    public void testInstalled() {
        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), bundle, false);
        Result result = check.execute();
        assertTrue(result.isOk());
    }
}
