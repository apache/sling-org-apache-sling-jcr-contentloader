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

import javax.jcr.RepositoryException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.commons.lang3.SystemUtils;
import org.apache.sling.jcr.contentloader.ContentCreator;
import org.apache.sling.jcr.contentloader.ContentReader;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>ZipReader</code>
 *
 * @since 2.0.4
 */
@Component(
        service = ContentReader.class,
        property = {
            Constants.SERVICE_VENDOR + "=The Apache Software Foundation",
            ContentReader.PROPERTY_EXTENSIONS + "=zip",
            ContentReader.PROPERTY_EXTENSIONS + "=jar",
            ContentReader.PROPERTY_TYPES + "=application/zip",
            ContentReader.PROPERTY_TYPES + "=application/java-archive"
        })
@Designate(ocd = ZipReader.Config.class)
public class ZipReader implements ContentReader {

    @ObjectClassDefinition(
            name = "%zipreader.config.name",
            description = "%zipreader.config.description",
            localization = "OSGI-INF/l10n/bundle")
    public @interface Config {
        @AttributeDefinition(
                name = "%zipreader.config.thresholdEntries.name",
                description = "%zipreader.config.thresholdEntries.description")
        long thresholdEntries() default 10000;

        @AttributeDefinition(
                name = "%zipreader.config.thresholdSize.name",
                description = "%zipreader.config.thresholdSize.description")
        long thresholdSize() default 1000000000; // 1 GB

        @AttributeDefinition(
                name = "%zipreader.config.thresholdRatio.name",
                description = "%zipreader.config.thresholdRatio.description")
        double thresholdRatio() default 10.0;
    }

    private static final String NT_FOLDER = "nt:folder";

    private long thresholdEntries;
    private long thresholdSize;
    private double thresholdRatio;

    @Activate
    void activate(Config config) {
        thresholdEntries = config.thresholdEntries();
        thresholdSize = config.thresholdSize();
        thresholdRatio = config.thresholdRatio();
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentReader#parse(java.net.URL, org.apache.sling.jcr.contentloader.ContentCreator)
     */
    @Override
    public void parse(java.net.URL url, ContentCreator creator) throws IOException, RepositoryException {
        try (InputStream is = url.openStream()) {
            parse(is, creator);
        }
    }

    /**
     * NOTE: made this a method to ease testing
     * @return true if this is a unix environment, false otherwise
     */
    static boolean isOsUnix() {
        return SystemUtils.IS_OS_UNIX;
    }

    static File createTempFile() throws IOException {
        File tmpFile;
        if (isOsUnix()) {
            FileAttribute<Set<PosixFilePermission>> attr =
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            tmpFile = Files.createTempFile("zipentry", ".tmp", attr).toFile();
        } else {
            tmpFile = Files.createTempFile("zipentry", ".tmp").toFile(); // NOSONAR
            if (!tmpFile.setReadable(true, true)) throw new IOException("Failed to set the temp file as readable");
            if (!tmpFile.setWritable(true, true)) throw new IOException("Failed to set the temp file as writable");
        }
        return tmpFile;
    }

    static void removeTempFile(File tempFile) {
        if (tempFile != null) {
            try {
                Files.delete(tempFile.toPath());
            } catch (IOException ioe) {
                Logger logger = LoggerFactory.getLogger(ZipReader.class);
                logger.warn("Failed to remove the temp file", ioe);
            }
        }
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentReader#parse(java.io.InputStream, org.apache.sling.jcr.contentloader.ContentCreator)
     */
    @Override
    public void parse(InputStream ins, ContentCreator creator) throws IOException, RepositoryException {
        File tempFile = null;
        try (ZipInputStream zis = new ZipInputStream(ins)) {
            creator.createNode(null, NT_FOLDER, null);
            ZipEntry entry;
            int totalSizeArchive = 0;
            int totalEntryArchive = 0;
            tempFile = createTempFile();
            do {
                entry = zis.getNextEntry();
                if (entry != null) {
                    totalEntryArchive++;

                    if (!entry.isDirectory()) {
                        // uncompress the entry to a temp file
                        totalSizeArchive =
                                copyZipEntryToTempFile(tempFile, zis, entry, totalSizeArchive, totalEntryArchive);

                        // now process the entry data from the data stored in the temp file
                        String name = entry.getName();
                        int pos = name.lastIndexOf('/');
                        if (pos != -1) {
                            creator.switchCurrentNode(name.substring(0, pos), NT_FOLDER);
                        }
                        try (FileInputStream fis = new FileInputStream(tempFile)) {
                            creator.createFileAndResourceNode(name, fis, null, entry.getTime());
                        }
                        creator.finishNode();
                        creator.finishNode();
                        if (pos != -1) {
                            creator.finishNode();
                        }
                    }
                    zis.closeEntry();
                }

            } while (entry != null);
            creator.finishNode();
        } finally {
            removeTempFile(tempFile);
        }
    }

    /**
     * Copy the contents of a zip entry to a temp file and check the
     * entry contents against the configured threshold for violations
     *
     * @param tempFile the temp file to write the entry to
     * @param zis the input stream for the zip file we are processing
     * @param entry the current zip entry
     * @param totalSizeArchive the total size of the archive so far
     * @param totalEntryArchive the total number of entries so far
     * @return the new totalSizeArchive value after reading the entry
     * @throws IOException
     */
    protected int copyZipEntryToTempFile(
            File tempFile, ZipInputStream zis, ZipEntry entry, int totalSizeArchive, int totalEntryArchive)
            throws IOException {
        int nBytes = -1;
        byte[] buffer = new byte[2048];
        int totalSizeEntry = 0;

        // read the entry to a temp file so we can check the contents against
        //  the configured thresholds
        try (InputStream in = new BufferedInputStream(CloseShieldInputStream.wrap(zis));
                OutputStream out = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            while ((nBytes = in.read(buffer)) > 0) { // Compliant
                out.write(buffer, 0, nBytes);
                totalSizeEntry += nBytes;
                totalSizeArchive += nBytes;

                double compressionRatio = (double) totalSizeEntry / entry.getCompressedSize();
                if (compressionRatio > thresholdRatio) {
                    // ratio between compressed and uncompressed data is highly suspicious, looks like a Zip Bomb Attack
                    throw new IOException("The compression ratio exceeded the allowed threshold");
                }
            }

            if (totalSizeArchive > thresholdSize) {
                // the uncompressed data size is too much for the application resource capacity
                throw new IOException("The total size of the archive exceeded the allowed threshold");
            }

            if (totalEntryArchive > thresholdEntries) {
                // too many entries in this archive, can lead to inodes exhaustion of the system
                throw new IOException("The total entries count of the archive exceeded the allowed threshold");
            }
        }
        return totalSizeArchive;
    }
}
