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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.VersionManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.sling.commons.mime.MimeTypeService;
import org.apache.sling.jcr.contentloader.ContentImportListener;
import org.apache.sling.jcr.contentloader.ContentImporter;
import org.apache.sling.jcr.contentloader.ContentReader;
import org.apache.sling.jcr.contentloader.ContentTypeUtil;
import org.apache.sling.jcr.contentloader.ImportOptions;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>DefaultContentImporter</code> is the default implementation of the ContentImporter service providing the following functionality:
 * <ul>
 * <li>Import content into the content repository.
 * </ul>
 */
@Component(
        service = ContentImporter.class,
        property = {
            Constants.SERVICE_DESCRIPTION + "=Apache Sling JCR Content Import Service",
            Constants.SERVICE_VENDOR + "=The Apache Software Foundation"
        })
public class DefaultContentImporter extends BaseImportLoader implements ContentHelper, ContentImporter {

    /**
     * The MimeTypeService used by the initial content initialContentLoader to resolve MIME types for files to be installed.
     */
    @Reference
    private MimeTypeService mimeTypeService;

    private final Logger logger = LoggerFactory.getLogger(DefaultContentImporter.class);

    @Reference(
            name = "contentReaderWhiteboard",
            cardinality = ReferenceCardinality.MANDATORY,
            service = ContentReaderWhiteboard.class)
    protected void bindContentReaderWhiteboard(final ContentReaderWhiteboard service) {
        this.contentReaderWhiteboard = service;
    }

    protected void unbindContentReaderWhiteboard(final ContentReaderWhiteboard service) {
        if (this.contentReaderWhiteboard == service) {
            this.contentReaderWhiteboard = null;
        }
    }

    /* (non-Javadoc)
     * @see org.apache.sling.jcr.contentloader.ContentImporter#importContent(javax.jcr.Node, java.lang.String, java.io.InputStream, org.apache.sling.jcr.contentloader.ImportOptions, org.apache.sling.jcr.contentloader.ContentImportListener)
     */
    @Override
    public void importContent(
            Node parent,
            String filename,
            InputStream contentStream,
            ImportOptions importOptions,
            ContentImportListener importListener)
            throws RepositoryException, IOException {
        logger.debug("initiate import of file name {}", filename);
        // special treatment for system view imports
        if (filename.endsWith(EXT_JCR_XML)) {
            importJcrXml(parent, filename, contentStream, importOptions, importListener);
            return;
        }

        final DefaultContentCreator contentCreator = new DefaultContentCreator(this);

        final String readerExtension = getContentReaderExtension(filename);
        final String name = toPlainName(filename, readerExtension);

        final ContentReader contentReader = getContentReader(filename, importOptions);

        importContent(contentCreator, contentReader, parent, name, contentStream, importOptions, importListener);
    }

    @Override
    public void importContent(
            final Node parent,
            final String name,
            final String contentType,
            final InputStream contentStream,
            final ImportOptions importOptions,
            final ContentImportListener importListener)
            throws RepositoryException, IOException {
        logger.debug("initiate import {} of type {}", name, contentType);
        // special treatment for system view imports
        if (ContentTypeUtil.TYPE_JCR_XML.equalsIgnoreCase(contentType)) {
            importJcrXml(parent, name, contentStream, importOptions, importListener);
            return;
        }

        final DefaultContentCreator contentCreator = new DefaultContentCreator(this);

        final String extension = ContentTypeUtil.getDefaultExtension(contentType);
        final ContentReader contentReader = getContentReader(extension, importOptions);

        importContent(contentCreator, contentReader, parent, name, contentStream, importOptions, importListener);
    }

    private void importContent(
            final DefaultContentCreator contentCreator,
            final ContentReader contentReader,
            final Node parent,
            final String name,
            final InputStream contentStream,
            final ImportOptions importOptions,
            final ContentImportListener importListener)
            throws RepositoryException, IOException {
        logger.debug("initiate import of {}", name);
        List<String> createdPaths = new ArrayList<>();
        contentCreator.init(importOptions, getContentReaders(), createdPaths, importListener);
        contentCreator.prepareParsing(parent, name);
        contentReader.parse(contentStream, contentCreator);

        // save changes
        Session session = parent.getSession();
        session.save();

        // finally checkin versionable nodes
        for (final Node versionable : contentCreator.getVersionables()) {
            VersionManager versionManager =
                    versionable.getSession().getWorkspace().getVersionManager();
            versionManager.checkin(versionable.getPath());
            if (importListener != null) {
                importListener.onCheckin(versionable.getPath());
            }
        }
    }

    private void importJcrXml(
            final Node parent,
            final String name,
            final InputStream contentStream,
            final ImportOptions importOptions,
            final ContentImportListener importListener)
            throws IOException, RepositoryException {
        logger.debug("import JCR XML: '{}'", name);
        boolean replace = importOptions != null && importOptions.isOverwrite();
        final Node node = importJcrXml(parent, name, contentStream, replace);
        if (node != null && importListener != null) {
            importListener.onCreate(node.getPath());
        }
    }

    // ---------- ContentHelper implementation ---------------------------------------------

    /* (non-Javadoc)
     * @see org.apache.sling.jcr.contentloader.internal.ContentHelper#getMimeType(java.lang.String)
     */
    @Override
    public String getMimeType(String name) {
        // local copy to not get NPE despite check for null due to concurrent unbind
        MimeTypeService mts = mimeTypeService;
        return (mts != null) ? mts.getMimeType(name) : null;
    }
}
