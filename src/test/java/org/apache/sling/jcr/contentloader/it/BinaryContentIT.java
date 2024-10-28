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
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.Bundle;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.factoryConfiguration;

/**
 * Test importing binary data
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class BinaryContentIT extends ContentloaderTestSupport {

    @Configuration
    public Option[] configuration() throws IOException {
        final String header = DEFAULT_PATH_IN_BUNDLE + ";path:=" + CONTENT_ROOT_PATH;
        final Multimap<String, String> content = ImmutableListMultimap.of(
                DEFAULT_PATH_IN_BUNDLE, "binary-data.json"
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
    public void importBinaryData() throws RepositoryException, IOException {
        final String imagePath = CONTENT_ROOT_PATH + "/binary-data";
        assertTrue("file node " + imagePath + " exists", session.itemExists(imagePath));
        Node image = session.getNode(imagePath);

        Node ntFile = image.getNode("file");
        assertEquals("file has node type 'nt:file'", "nt:file", ntFile.getPrimaryNodeType().getName());

        Node ntResource = ntFile.getNode("jcr:content");
        assertEquals("jcr:content has node type 'nt:resource'", "nt:resource", ntResource.getPrimaryNodeType().getName());
        assertTrue("jcr:content has property 'jcr:data'",  ntResource.hasProperty("jcr:data"));

        Property property = ntResource.getProperty("jcr:data");
        assertEquals("jcr:data  is a binary property", PropertyType.BINARY, property.getType());

        // read the data, encode it to base64 and assert it matches the input json
        InputStream inputStream = property.getBinary().getStream();
        byte[] bytes = IOUtils.toByteArray(inputStream);
        String base64  = Base64.getEncoder().encodeToString(bytes);
        assertEquals("binary data is the same after round-trip", "iVBORw0KGgoA", base64);
    }

}
