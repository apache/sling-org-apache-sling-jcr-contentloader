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
package org.apache.sling.jcr.contentloader.internal.readers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Date;

import javax.jcr.RepositoryException;

import junit.framework.TestCase;

public class XmlReaderTest extends TestCase {

    private XmlReader reader;
    private MockContentCreator creator;

    /**
     * Test the XmlReader with an XSLT transform.
     */
    public void testXmlReader() throws Exception {
        File file = new File("src/test/resources/reader/sample.xml");
        final URL testdata = file.toURI().toURL();
        reader.parse(testdata, creator);
        assertEquals("Did not create expected number of nodes", 1, creator.size());
    }

    /**
     * Test inclusion of binary files.
     */
    public void testCreateFile() throws Exception {
        File input = new File("src/test/resources/reader/filesample.xml");
        final URL testdata = input.toURI().toURL();
        reader.parse(testdata, creator);
        assertEquals("Did not create expected number of files", 2, creator.filesCreated.size());
        MockContentCreator.FileDescription file = creator.filesCreated.get(0);
        try {
            file.data.available();
            TestCase.fail("Did not close inputstream");
        } catch (IOException ignore) {
            // Expected
        }
        assertEquals("mimeType mismatch", "application/test", file.mimeType);
        assertEquals("lastModified mismatch", XmlReader.FileDescription.createDateFormat().parse("1977-06-01T07:00:00+0100"),
                new Date(file.lastModified));
        assertEquals("Could not read file", "This is a test file.", file.content);

    }

    public void testCreateFileWithNullLocation() throws Exception {
        File input = new File("src/test/resources/reader/filesample.xml");
        final FileInputStream ins = new FileInputStream(input);
        try {
            reader.parse(ins, creator);
            assertEquals("Created files when we shouldn't have", 0, creator.filesCreated.size());
        } finally {
            ins.close();
        }
    }

    public void testUseOSLastModified() throws RepositoryException, IOException {
        File input = new File("src/test/resources/reader/datefallbacksample.xml");
        final URL testdata = input.toURI().toURL();
        reader.parse(testdata, creator);
        File file = new File("src/test/resources/reader/testfile.txt");
        long originalLastModified = file.lastModified();
        assertEquals("Did not create expected number of files", 1, creator.filesCreated.size());
        MockContentCreator.FileDescription fileDescription = creator.filesCreated.get(0);
        assertEquals("Did not pick up last modified date from file", originalLastModified,
                fileDescription.lastModified);

    }

    protected void setUp() throws Exception {
        super.setUp();
        reader = new XmlReader();
        reader.activate();
        creator = new MockContentCreator();
    }
}
