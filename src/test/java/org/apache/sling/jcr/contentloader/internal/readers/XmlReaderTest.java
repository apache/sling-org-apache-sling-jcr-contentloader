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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.Map;

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

    /**
     * Test the properties and types were processed
     */
    public void testCreateTypesAndProperties() throws Exception {
        File input = new File("src/test/resources/reader/filesample.xml");
        final URL testdata = input.toURI().toURL();
        reader.parse(testdata, creator);

        assertEquals(1, creator.size());
        Map<String, Object> map = creator.get(0);
        assertEquals("nodeName", map.get("name"));
        assertEquals("type", map.get("primaryNodeType"));
        assertArrayEquals(new String[] {"mixtype1", "mixtype2"}, (String[])map.get("mixinNodeTypes"));

        @SuppressWarnings("unchecked")
        Map<String, Object> propsMap = (Map<String, Object>)map.get("properties");
        assertNotNull(propsMap);
        assertEquals("propValue", propsMap.get("propName"));
        assertArrayEquals(new String[] {"propValue1", "propValue2"}, (String[])propsMap.get("multiPropName"));
        assertNull(propsMap.get("multiPropName2"));
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

    private void malformedXmlTest(String xml, String expectedMsg) throws Exception {
        try (ByteArrayInputStream is = new ByteArrayInputStream(xml.getBytes())) {
            IOException ioe = assertThrows(IOException.class, () -> reader.parse(is, creator));
            assertEquals(expectedMsg, ioe.getMessage());
        }
    }

    public void testMalformedXmlUnexpectedPropertyElement() throws Exception {
        malformedXmlTest("<property/>",
                "XML file does not seem to contain valid content xml. Expected name element for property in : null");
    }

    public void testMalformedXmlUnexpectedNameElement() throws Exception {
        malformedXmlTest("<name></name>",
                "XML file does not seem to contain valid content xml. Unexpected name element in : null");
    }

    public void testMalformedXmlUnexpectedValueElement() throws Exception {
        malformedXmlTest("<value></value>",
                "XML file does not seem to contain valid content xml. Unexpected value element in : null");
    }

    public void testMalformedXmlUnexpectedValuesElement() throws Exception {
        malformedXmlTest("<values></values>",
                "XML file does not seem to contain valid content xml. Unexpected values element in : null");
    }

    public void testMalformedXmlUnexpectedTypeElement() throws Exception {
        malformedXmlTest("<type></type>",
                "XML file does not seem to contain valid content xml. Unexpected type element in : null");
    }

    public void testMalformedXmlUnexpectedPrimaryNodeTypeElement() throws Exception {
        malformedXmlTest("<primaryNodeType></primaryNodeType>",
                "Element is not allowed at this location: primaryNodeType in null");
    }

    public void testMalformedXmlUnexpectedMixinNodeTypeElement() throws Exception {
        malformedXmlTest("<mixinNodeType></mixinNodeType>",
                "Element is not allowed at this location: mixinNodeType in null");
    }

}
