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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

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
            ZipEntry dirEntry = new ZipEntry("folder/");
            zipOut.putNextEntry(dirEntry);
            zipOut.closeEntry();

            ZipEntry fileEntry = new ZipEntry("folder/entry");
            zipOut.putNextEntry(fileEntry);
            zipOut.write("Hello subfolder".getBytes());
            zipOut.closeEntry();

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
    public void noViolations() throws Exception {
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
        assertEquals(2, creator.filesCreated.size());
        assertEquals("Hello subfolder", creator.filesCreated.get(0).content);
        assertEquals("Hello", creator.filesCreated.get(1).content);
    }

    @Test
    public void fromUrlNoViolations() throws Exception {
        // generate a zip
        byte[] zipBytes = generateZip(zipOut -> {
            ZipEntry entry = new ZipEntry("entry");
            zipOut.putNextEntry(entry);
            zipOut.write("Hello From File".getBytes());
            zipOut.closeEntry();
        });
        File tmpFile = null;
        try {
            tmpFile = ZipReader.createTempFile();
            try (FileOutputStream outStream = new FileOutputStream(tmpFile)) {
                outStream.write(zipBytes);
            }
            reader.parse(tmpFile.toURI().toURL(), creator);
        } finally {
            ZipReader.removeTempFile(tmpFile);
        }
        assertEquals(2, creator.filesCreated.size());
        assertEquals("Hello subfolder", creator.filesCreated.get(0).content);
        assertEquals("Hello From File", creator.filesCreated.get(1).content);
    }

    @Test
    public void totalEntryCountExceeded() throws Exception {
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
    public void totalSizeExceeded() throws Exception {
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
    public void compressionRatioExceed() throws Exception {
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

    @Test
    public void createTempFile() throws Exception {
        File tempFile = null;
        try {
            tempFile = ZipReader.createTempFile();
            assertNotNull(tempFile);
            assertTrue(tempFile.canRead());
            assertTrue(tempFile.canWrite());
        } finally {
            ZipReader.removeTempFile(tempFile);
        }
    }

    @Test
    public void createTempFileForceNotUnix() throws Exception {
        doWorkAsNotUnix(() -> {
            createTempFile();
        });
    }

    @Test(expected = org.junit.Test.None.class)
    public void removeTempFileNull() throws Exception {
        // should silently do nothing
        ZipReader.removeTempFile(null);
    }

    @Test
    public void removeTempFile() throws Exception {
        File tempFile = ZipReader.createTempFile();
        assertNotNull(tempFile);
        ZipReader.removeTempFile(tempFile);
        assertFalse(tempFile.exists());
    }

    @Test
    public void removeTempFileLogWarningOnIOException() throws Exception {
        File tempFile = null;
        try {
            final File finalTempFile = ZipReader.createTempFile();
            tempFile = finalTempFile;
            try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class);) {
                filesMock.when(() -> Files.delete(finalTempFile.toPath()))
                    .thenThrow(new IOException("simulating ioexception during delete"));

                String err = doWorkWithCapturedSystemErr(() -> ZipReader.removeTempFile(finalTempFile));
                assertTrue("Expected warning logged about IOException wile removing the temp file",
                        err.contains("WARN org.apache.sling.jcr.contentloader.internal.readers.ZipReader - Failed to remove the temp file"));
            }
        } finally {
            ZipReader.removeTempFile(tempFile);
            if (tempFile != null) {
                assertFalse(tempFile.exists());
            }
        }
    }

    @Test
    public void createTempFileIOExceptionOnSetReadFailure() throws Exception {
        final File fileMock = Mockito.mock(File.class);
        Mockito.when(fileMock.setReadable(true, true)).thenReturn(false);
        createTempFileIOException(fileMock, "Failed to set the temp file as readable");
    }

    @Test
    public void createTempFileIOExceptionOnSetWriteFailure() throws Exception {
        final File fileMock = Mockito.mock(File.class);
        Mockito.when(fileMock.setReadable(true, true)).thenReturn(true);
        Mockito.when(fileMock.setWritable(true, true)).thenReturn(false);
        createTempFileIOException(fileMock, "Failed to set the temp file as writable");
    }

    /**
     * Helper that simulates an IOException thrown during creation of temp file
     * 
     * @param fileMock the file mock to use
     * @param expectedMsg the message expected in the IOException
     */
    private void createTempFileIOException(final File fileMock, final String expectedMsg) throws Exception {
        doWorkAsNotUnix(() -> {
            Path pathMock = (Path) Proxy.newProxyInstance(
                    Path.class.getClassLoader(), 
                    new Class[] { Path.class }, 
                    (proxy, method, methodArgs) -> {
                      if (method.getName().equals("toFile")) {
                          return fileMock;
                      } else {
                          throw new UnsupportedOperationException(
                            "Unsupported method: " + method.getName());
                      }
                  });

            try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class);) {
                filesMock.when(() -> Files.createTempFile("zipentry", ".tmp"))
                    .thenReturn(pathMock);
                IOException ioe = assertThrows(IOException.class, () -> ZipReader.createTempFile());
                assertEquals(expectedMsg, ioe.getMessage());
            }
        });
    }

    /**
     * Variation of Runnable that allows exception to be thrown
     */
    @FunctionalInterface
    static interface RunnableWithExceptions {
        void run() throws Exception;
    }

    /**
     * Helper to do some do some work while the OS is simulated as not unix
     * @param worker the worker
     */
    static void doWorkAsNotUnix(RunnableWithExceptions worker) throws Exception {
        try (MockedStatic<ZipReader> zipReaderMock = Mockito.mockStatic(ZipReader.class, CALLS_REAL_METHODS);) {
            zipReaderMock.when(() -> ZipReader.isOsUnix())
                .thenReturn(false);

            // do the work
            worker.run();
        }
    }

    /**
     * Helper to do some do some work while capturing the output to System.err
     * @param worker the worker
     * @return the text that was captured
     */
    static String doWorkWithCapturedSystemErr(Runnable worker) {
        // Create a stream to hold the output
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        // stash the old stream
        PrintStream old = System.err;
        try {
            // switch to the capture stream
            System.setErr(ps);

            //do the work
            worker.run();
        } finally {
            // Put things back
            System.err.flush();
            System.setErr(old);
        }
        return baos.toString();
    }

}
