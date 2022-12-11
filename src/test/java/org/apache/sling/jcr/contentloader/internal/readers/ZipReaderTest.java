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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.Test;

public class ZipReaderTest {

    private ZipReader reader;
    private MockContentCreator creator;

    @Before
    public void setUp() throws Exception {
        reader = new ZipReader();
        ZipReader.Config config = new ZipReader.Config() {

            @Override
            public Class<? extends Annotation> annotationType() {
                throw new UnsupportedOperationException();
            }

            @Override
            public long thresholdEntries() {
                return 5;
            }

            @Override
            public long thresholdSize() {
                return 5000;
            }

            @Override
            public double thresholdRatio() {
                return 3.0;
            }

        };
        reader.activate(config);
        creator = new MockContentCreator();
    }

    private interface ZipPopulate {
        public void populate(ZipOutputStream zipOut) throws IOException;
    }
    protected byte[] generateZip(ZipPopulate populateFn) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                ZipOutputStream zipOut = new ZipOutputStream(out)) {
            populateFn.populate(zipOut);
            return out.toByteArray();
        }
    }

    protected byte[] randomAlphanumericString(int targetStringLength) {
        int leftLimit = 48; // numeral '0'
        int rightLimit = 122; // letter 'z'
        Random random = new Random();

        String generatedString = random.ints(leftLimit, rightLimit + 1)
          .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
          .limit(targetStringLength)
          .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
          .toString();

        return generatedString.getBytes();
    }

    @Test
    public void testZipReaderNoViolations() throws Exception {
        // generate a zip
        byte[] zipBytes = generateZip(zipOut -> {
            ZipEntry entry = new ZipEntry("entry");
            zipOut.putNextEntry(entry);
            zipOut.write("Hello".getBytes());
            zipOut.closeEntry();
        });
        try (ByteArrayInputStream in = new ByteArrayInputStream(zipBytes)) {
            reader.parse(in, creator);
        }
        assertEquals(1, creator.filesCreated.size());
        assertEquals("Hello", creator.filesCreated.get(0).content);
    }

    @Test
    public void testZipReaderTotalEntryCountExceeded() throws Exception {
        // generate a zip with too many entries
        byte[] zipBytes = generateZip(zipOut -> {
            for (int i=0; i < 6; i++) {
                ZipEntry entry = new ZipEntry(String.format("entry%d", i));
                zipOut.putNextEntry(entry);
                zipOut.write(String.format("Hello %d", i).getBytes());
                zipOut.closeEntry();
            }
        });
        try (ByteArrayInputStream in = new ByteArrayInputStream(zipBytes)) {
            IOException threw = assertThrows(IOException.class, () -> {
                reader.parse(in, creator);
            });
            assertEquals("The total entries count of the archive exceeded the allowed threshold", threw.getMessage());
        }
    }

    @Test
    public void testZipReaderTotalSizeExceeded() throws Exception {
        // generate a zip with too many bytes
        byte[] zipBytes = generateZip(zipOut -> {
            ZipEntry entry = new ZipEntry("entry");
            zipOut.putNextEntry(entry);
            zipOut.write(randomAlphanumericString(5001));
            zipOut.closeEntry();
        });

        try (ByteArrayInputStream in = new ByteArrayInputStream(zipBytes)) {
            IOException threw = assertThrows(IOException.class, () -> {
                reader.parse(in, creator);
            });
            assertEquals("The total size of the archive exceeded the allowed threshold", threw.getMessage());
        }
    }

    @Test
    public void testZipReaderCompressionRatioExceed() throws Exception {
        // generate a zip with too high if a compression ratio
        byte[] zipBytes = generateZip(zipOut -> {
            ZipEntry entry = new ZipEntry("entry");
            zipOut.putNextEntry(entry);
            // a string of all the same characters will have a high
            // compression ratio
            for (int i=0; i < 1000; i++) {
                zipOut.write('a');
            }
            zipOut.closeEntry();
        });

        try (ByteArrayInputStream in = new ByteArrayInputStream(zipBytes)) {
            IOException threw = assertThrows(IOException.class, () -> {
                reader.parse(in, creator);
            });
            assertEquals("The compression ratio exceeded the allowed threshold", threw.getMessage());
        }
    }

}
