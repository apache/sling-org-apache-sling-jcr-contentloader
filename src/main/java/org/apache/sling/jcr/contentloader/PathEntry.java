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
package org.apache.sling.jcr.contentloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.function.Function;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.apache.sling.commons.osgi.ManifestHeader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.Bundle;

/**
 * A path entry from the manifest for initial content.
 */
public class PathEntry extends ImportOptions {

    /** The manifest header to specify initial content to be loaded. */
    public static final String CONTENT_HEADER = "Sling-Initial-Content";

    /**
     * The overwrite directive specifying if content should be overwritten or
     * just initially added.
     */
    public static final String OVERWRITE_DIRECTIVE = "overwrite";

    /**
     * The overwriteProperties directive specifying if content properties 
     * should be overwritten or just initially added.
     */
    public static final String OVERWRITE_PROPERTIES_DIRECTIVE = "overwriteProperties";
    
    /**
     * The overwriteProperties directive specifying if content properties 
     * should be overwritten or just initially added.
     */
    public static final String MERGE_PROPERTIES_DIRECTIVE = "mergeProperties";
    
    /**
     * The overwriteProperties directive specifying if content properties 
     * should be overwritten or just initially added.
     */
    public static final String MERGE_NODES_DIRECTIVE = "merge";

    /** The uninstall directive specifying if content should be uninstalled. */
    public static final String UNINSTALL_DIRECTIVE = "uninstall";

    /**
     * The path directive specifying the target node where initial content will
     * be loaded.
     */
    public static final String PATH_DIRECTIVE = "path";

    /**
     * The workspace into which the content will be loaded.
     */
    public static final String WORKSPACE_DIRECTIVE = "workspace";

    /**
     * The checkin directive specifying whether versionable nodes should be
     * checked in
     */
    public static final String CHECKIN_DIRECTIVE = "checkin";

    /**
     * The autoCheckout directive specifying whether versionable nodes should be
     * checked out when necessary
     */
    public static final String AUTOCHECKOUT_DIRECTIVE = "autoCheckout";

    /**
     * The ignore content readers directive specifying whether the available 
     * {@link org.apache.sling.jcr.contentloader.ContentReader}s should be used during
     * content loading. This is a string value that defaults to the emptystring.
     * @since 2.0.4
     */
    public static final String IGNORE_CONTENT_READERS_DIRECTIVE = "ignoreImportProviders";

    private final boolean propertyMerge;
    
    private final boolean nodeMerge;

    /** The path for the initial content. */
    private final String path;

    /** Should existing content be overwritten? */
    private final boolean overwrite;

    /** Should existing content properties be overwritten? */
    private final boolean overwriteProperties;

    /** Should existing content be uninstalled? */
    private final boolean uninstall;

    /** Should versionable nodes be checked in? */
    private final boolean checkin;

    /** Should versionable nodes be auto checked out when necessary? */
    private final boolean autoCheckout;
    
    /** Which content readers should be ignored? @since 2.0.4 */
    private final List<String> ignoreContentReaders;

    /**
     * Target path where initial content will be loaded. If it´s null then
     * target node is the root node
     */
    private final String target;

    /** Workspace to import into. */
    private final String workspace;

    private long lastModified;

    /** 
     * Parses the "Sling-Initial-Content" header from the given manifest and returns the resolved PathEntries
     * 
     * @param manifest the manifest
     * @param bundleLastModified the timestamp when the bundle has been last modified or -1 if not known
     * @return an iterator over the parsed {@code PathEntry} items or {@code null} in case no "Sling-Initial-Content" header was found in the manifest
     */
    public static @Nullable Iterator<PathEntry> getContentPaths(@NotNull final Manifest manifest, long bundleLastModified) {
        // convert to map of right type (Attributes has Attributes.Name as key type, not String), so simple casting won't do
        Map<String, String> headers = manifest.getMainAttributes().entrySet().stream().collect(Collectors.toMap( e -> e.getKey().toString(),  e-> e.getValue().toString()));
        return getContentPaths(headers, bundleLastModified);
    }

    /** 
     * Parses the "Sling-Initial-Content" header from the given bundle and returns the resolved PathEntries
     * 
     * @param bundle the bundle
     * @return an iterator over the parsed {@code PathEntry} items or {@code null} in case no "Sling-Initial-Content" header was found in the bundle's manifest
     */
    public static @Nullable Iterator<PathEntry> getContentPaths(final Bundle bundle) {
        return getContentPaths(toMap(bundle.getHeaders()), bundle.getLastModified());
    }

    /** 
     * Parses the "Sling-Initial-Content" header from the given headers and returns the resolved PathEntries
     * 
     * @param headers the manifest headers
     * @param bundleLastModified the timestamp when the bundle has been last modified or -1 if not known
     * @return an iterator over the parsed {@code PathEntry} items or {@code null} in case no "Sling-Initial-Content" header was found
     */
    public static @Nullable Iterator<PathEntry> getContentPaths(final Map<String, String> headers, long bundleLastModified) {
        final List<PathEntry> entries = new ArrayList<>();
        String bundleLastModifiedStamp = headers.get("Bnd-LastModified");
        if ( bundleLastModifiedStamp != null ) {
            bundleLastModified = Math.min(bundleLastModified, Long.parseLong(bundleLastModifiedStamp));
        }
        final String root = headers.get(CONTENT_HEADER);
        if (root != null) {
            final ManifestHeader header = ManifestHeader.parse(root);
            for (final ManifestHeader.Entry entry : header.getEntries()) {
                
                entries.add(new PathEntry(entry, bundleLastModified ));
            }
        }

        if (entries.isEmpty()) {
            return null;
        }
        return entries.iterator();
    }

    private static Map<String, String> toMap(Dictionary<String, String> dict) {
        List<String> keys = Collections.list(dict.keys());
        return keys.stream()
                   .collect(Collectors.toMap(Function.identity(), dict::get));
    }
 
    public PathEntry(ManifestHeader.Entry entry, long bundleLastModified) {
        this.path = entry.getValue();
        this.lastModified = bundleLastModified;

        // check for directives

        // merge directive
        final String mergeProperties = entry.getDirectiveValue(MERGE_PROPERTIES_DIRECTIVE);
        if (mergeProperties != null) {
            this.propertyMerge = Boolean.valueOf(mergeProperties);
        } else {
            this.propertyMerge = false;
        }
        
        // merge directive
        final String mergeNodes = entry.getDirectiveValue(MERGE_NODES_DIRECTIVE);
        if (mergeNodes != null) {
            this.nodeMerge = Boolean.valueOf(mergeProperties);
        } else {
            this.nodeMerge = false;
        }
        
        // overwrite directive
        final String overwriteValue = entry.getDirectiveValue(OVERWRITE_DIRECTIVE);
        if (overwriteValue != null) {
            this.overwrite = Boolean.valueOf(overwriteValue);
        } else {
            this.overwrite = false;
        }

        // overwriteProperties directive
        final String overwritePropertiesValue = entry.getDirectiveValue(OVERWRITE_PROPERTIES_DIRECTIVE);
        if (overwritePropertiesValue != null) {
            this.overwriteProperties = Boolean.valueOf(overwritePropertiesValue);
        } else {
            this.overwriteProperties = false;
        }
        
 
        // uninstall directive
        final String uninstallValue = entry.getDirectiveValue(UNINSTALL_DIRECTIVE);
        if (uninstallValue != null) {
            this.uninstall = Boolean.valueOf(uninstallValue);
        } else {
            this.uninstall = this.overwrite;
        }

        // path directive
        final String pathValue = entry.getDirectiveValue(PATH_DIRECTIVE);
        if (pathValue != null) {
            this.target = pathValue;
        } else {
            this.target = null;
        }

        // checkin directive
        final String checkinValue = entry.getDirectiveValue(CHECKIN_DIRECTIVE);
        if (checkinValue != null) {
            this.checkin = Boolean.valueOf(checkinValue);
        } else {
            this.checkin = false;
        }

        // autoCheckout directive
        final String autoCheckoutValue = entry.getDirectiveValue(AUTOCHECKOUT_DIRECTIVE);
        if (autoCheckoutValue != null) {
            this.autoCheckout = Boolean.valueOf(autoCheckoutValue);
        } else {
            this.autoCheckout = true;
        }

        // expand directive
        this.ignoreContentReaders = new ArrayList<>();
        final String expandValue = entry.getDirectiveValue(IGNORE_CONTENT_READERS_DIRECTIVE);
        if ( expandValue != null && expandValue.length() > 0 ) {
            final StringTokenizer st = new StringTokenizer(expandValue, ",");
            while ( st.hasMoreTokens() ) {
                this.ignoreContentReaders.add(st.nextToken());
            }
        }

        // workspace directive
        final String workspaceValue = entry.getDirectiveValue(WORKSPACE_DIRECTIVE);
        if (pathValue != null) {
            this.workspace = workspaceValue;
        } else {
            this.workspace = null;
        }
    }
    
    public long getLastModified() {
        return lastModified;
    }
    
    public String getPath() {
        return this.path;
    }

    /* (non-Javadoc)
     * @see org.apache.sling.jcr.contentloader.ImportOptions#isOverwrite()
     */
    public boolean isOverwrite() {
        return this.overwrite;
    }

    /* (non-Javadoc)
     * @see org.apache.sling.jcr.contentloader.ImportOptions#isPropertyOverwrite()
     */

    public boolean isPropertyOverwrite() {
        return this.overwriteProperties;
    }

    public boolean isUninstall() {
        return this.uninstall;
    }

    /* (non-Javadoc)
     * @see org.apache.sling.jcr.contentloader.ImportOptions#isCheckin()
     */
    public boolean isCheckin() {
        return this.checkin;
    }
    
    /* (non-Javadoc)
     * @see org.apache.sling.jcr.contentloader.ImportOptions#isAutoCheckout()
     */

    public boolean isAutoCheckout() {
        return this.autoCheckout;
    }

    /* (non-Javadoc)
     * @see org.apache.sling.jcr.contentloader.ImportOptions#isIgnoredImportProvider(java.lang.String)
     */
    public boolean isIgnoredImportProvider(String extension) {
        if ( extension.startsWith(".") ) {
            extension = extension.substring(1);
        }
        return this.ignoreContentReaders.contains(extension);
    }

    public String getTarget() {
        return target;
    }

    public String getWorkspace() {
        return workspace;
    }


    public boolean isPropertyMerge() {
        return this.propertyMerge;
    }


    public boolean isMerge() {
        return this.nodeMerge;
    }
}
