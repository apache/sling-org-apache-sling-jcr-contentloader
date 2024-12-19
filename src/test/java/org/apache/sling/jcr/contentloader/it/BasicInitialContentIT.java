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

import javax.jcr.RepositoryException;

import java.io.IOException;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.factoryConfiguration;

/**
 * Basic test of a bundle that provides initial content
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class BasicInitialContentIT extends ContentloaderTestSupport {

    @Configuration
    public Option[] configuration() throws IOException {
        final String header = DEFAULT_PATH_IN_BUNDLE + ";path:=" + CONTENT_ROOT_PATH;
        final Multimap<String, String> content = ImmutableListMultimap.of(
                DEFAULT_PATH_IN_BUNDLE, "basic-content.json",
                DEFAULT_PATH_IN_BUNDLE, "simple-folder/test1.txt",
                DEFAULT_PATH_IN_BUNDLE, "folder-with-descriptor.json",
                DEFAULT_PATH_IN_BUNDLE, "folder-with-descriptor/test2.txt");
        final Option bundle = buildInitialContentBundle(header, content);
        // configure the health check component
        Option hcConfig = factoryConfiguration("org.apache.sling.jcr.contentloader.hc.BundleContentLoadedCheck")
                .put("hc.tags", new String[] {TAG_TESTING_CONTENT_LOADING})
                .asOption();
        return options(baseConfiguration(), hcConfig, bundle);
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
        final String testNodePath = CONTENT_ROOT_PATH + "/basic-content/test-node";
        assertTrue("Expecting initial content to be installed", session.itemExists(testNodePath));
        assertEquals(
                "Expecting foo=bar",
                "bar",
                session.getNode(testNodePath).getProperty("foo").getString());
    }

    @Test
    public void folderWithoutDescriptor() throws RepositoryException {
        final String folderPath = CONTENT_ROOT_PATH + "/simple-folder";
        assertTrue("folder node " + folderPath + " exists", session.itemExists(folderPath));
        assertEquals(
                "folder has node type 'sling:Folder'",
                "sling:Folder",
                session.getNode(folderPath).getPrimaryNodeType().getName());

        final String filePath = CONTENT_ROOT_PATH + "/simple-folder/test1.txt";
        assertTrue("file node " + filePath + " exists", session.itemExists(filePath));
        assertEquals(
                "file has node type 'nt:file'",
                "nt:file",
                session.getNode(filePath).getPrimaryNodeType().getName());
    }

    @Test
    public void folderWithDescriptor() throws RepositoryException {
        final String folderPath = CONTENT_ROOT_PATH + "/folder-with-descriptor";
        assertTrue("folder node " + folderPath + " exists", session.itemExists(folderPath));
        assertEquals(
                "folder has node type 'sling:OrderedFolder'",
                "sling:OrderedFolder",
                session.getNode(folderPath).getPrimaryNodeType().getName());

        final String filePath = CONTENT_ROOT_PATH + "/folder-with-descriptor/test2.txt";
        assertTrue("file node " + filePath + " exists", session.itemExists(filePath));
        assertEquals(
                "file has node type 'nt:file'",
                "nt:file",
                session.getNode(filePath).getPrimaryNodeType().getName());
    }
}
