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

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.Bundle;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.factoryConfiguration;

/**
 * SLING-1130 test setting Date properties
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class SLING1130InitialContentIT extends ContentloaderTestSupport {

    @Configuration
    public Option[] configuration() throws IOException {
        final String header = DEFAULT_PATH_IN_BUNDLE + ";path:=" + CONTENT_ROOT_PATH;
        final Multimap<String, String> content = ImmutableListMultimap.of(
                DEFAULT_PATH_IN_BUNDLE, "SLING-1130.json"
        );
        final Option bundle = buildInitialContentBundle(header, content);
        // configure the health check component
        Option hcConfig = factoryConfiguration("org.apache.sling.jcr.contentloader.hc.BundleContentLoadedCheck")
                .put("hc.tags", new String[]{TAG_TESTING_CONTENT_LOADING})
                .asOption();
        return new Option[]{
                baseConfiguration(),
                hcConfig,
                bundle
        };
    }

    /* (non-Javadoc)
     * @see org.apache.sling.jcr.contentloader.it.ContentloaderTestSupport#setup()
     */
    @Before
    @Override
    public void setup() throws Exception {
        super.setup();

        waitForContentLoaded();
    }

    @Test
    public void bundleStarted() {
        final Bundle b = findBundle(BUNDLE_SYMBOLICNAME);
        assertNotNull("Expecting bundle to be found:" + BUNDLE_SYMBOLICNAME, b);
        assertEquals("Expecting bundle to be active:" + BUNDLE_SYMBOLICNAME, Bundle.ACTIVE, b.getState());
    }

    @Test
    public void initialContentInstalled() throws RepositoryException {
        final String nodePath = CONTENT_ROOT_PATH + "/SLING-1130";
        assertTrue("Expecting initial content to be installed", session.itemExists(nodePath));
        // 2023-05-24T10:36:11.401Z
        assertEquals("p1 property has node type 'Date'", PropertyType.DATE, session.getNode(nodePath).getProperty("p1").getType());
        // 2009-09-24T16:32:57.948-07:00
        assertEquals("p2 property has node type 'Date'", PropertyType.DATE, session.getNode(nodePath).getProperty("p2").getType());
        // 2022-12-18T16:49:44.420+03:00
        assertEquals("p3 property has node type 'Date'", PropertyType.DATE, session.getNode(nodePath).getProperty("p3").getType());
        // ECMA date: Sun Jun 04 2023 10:40:01 GMT+0000
        assertEquals("p4 property has node type 'STRING'", PropertyType.STRING, session.getNode(nodePath).getProperty("p4").getType());
        // ECMA date: Sat Dec 03 2022 14:39:30 GMT+0300
        assertEquals("p5 property has node type 'STRING'", PropertyType.STRING, session.getNode(nodePath).getProperty("p5").getType());
     }
}
