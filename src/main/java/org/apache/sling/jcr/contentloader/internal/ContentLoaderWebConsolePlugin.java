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

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.GenericServlet;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.sling.api.request.ResponseUtil;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.contentloader.PathEntry;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

@Component(
        service = javax.servlet.Servlet.class,
        property = {
            Constants.SERVICE_VENDOR + "=The Apache Software Foundation",
            Constants.SERVICE_DESCRIPTION + "=Apache Sling JCR Content Loader Web Console Plugin",
            "felix.webconsole.label=" + ContentLoaderWebConsolePlugin.LABEL,
            "felix.webconsole.title=JCR Content Loader",
            "felix.webconsole.category=Sling",
            "felix.webconsole.css=" + ContentLoaderWebConsolePlugin.RES_LOC + "main.css"
        })
@SuppressWarnings("serial")
public class ContentLoaderWebConsolePlugin extends GenericServlet {

    public static final String LABEL = "jcr-content-loader";
    protected static final String RES_LOC = LABEL + "/res/ui/";

    @Reference
    transient SlingRepository repository;

    @Reference
    transient BundleHelper bundleHelper;

    transient BundleContext context;

    @Activate()
    private void activate(BundleContext context) {
        this.context = context;
    }

    @Override
    public void service(final ServletRequest req, final ServletResponse res) throws IOException {
        Session session = null;
        PrintWriter pw = res.getWriter();
        try {
            session = repository.loginService(null, null);
            pw.print("<p class='statline ui-state-highlight'>Apache Sling JCR Content Loader");
            pw.print("</p>");
            pw.println("<table class='nicetable'><thead>");
            pw.println(
                    "<tr><th>Bundle</th><th>Path Entries</th><th>Content Loaded Successfully?</th><th>Uninstall Paths (format: JCR workspace:path)</th></tr>");
            pw.println("</thead><tbody>");
            int bundleNo = 1;
            for (final Bundle bundle : context.getBundles()) {
                String contentHeader = bundle.getHeaders().get(PathEntry.CONTENT_HEADER);
                if (contentHeader != null) {
                    printBundleInfoTableRow(pw, req, session, bundle, (bundleNo++ % 2 == 0));
                }
            }
            pw.println("</tbody></table>");
        } catch (RepositoryException e) {
            pw.println("Error accessing the underlying repository");
            e.printStackTrace(pw);
        } finally {
            if (session != null) {
                session.logout();
            }
        }
    }

    void printBundleInfoTableRow(
            PrintWriter pw, final ServletRequest req, Session session, Bundle bundle, boolean isEven)
            throws RepositoryException {
        Map<String, Object> contentInfoMap = bundleHelper.getBundleContentInfo(session, bundle, false);
        // release lock as early as possible
        bundleHelper.unlockBundleContentInfo(session, bundle, false, null);

        String[] uninstallPaths = (String[]) contentInfoMap.get(BundleContentLoaderListener.PROPERTY_UNINSTALL_PATHS);
        final String uninstallPathsString;
        if (uninstallPaths == null) {
            uninstallPathsString = "-";
        } else {
            uninstallPathsString =
                    Arrays.stream(uninstallPaths).map(ResponseUtil::escapeXml).collect(Collectors.joining("<br/>"));
        }
        Object loadedDate = contentInfoMap.get(BundleContentLoaderListener.PROPERTY_CONTENT_LOADED_AT);
        final String loadedDetails;
        if (!(loadedDate instanceof Calendar)) {
            loadedDetails = "?";
        } else {
            Calendar calendar = Calendar.class.cast(loadedDate);
            String formatterDate = DateTimeFormatter.ISO_ZONED_DATE_TIME
                    .withZone(calendar.getTimeZone().toZoneId())
                    .withLocale(req.getLocale())
                    .format(calendar.toInstant());
            String loadedBy =
                    String.valueOf(contentInfoMap.get(BundleContentLoaderListener.PROPERTY_CONTENT_LOADED_BY));
            loadedDetails = String.format("%s<br/>by Sling ID %s", formatterDate, ResponseUtil.escapeXml(loadedBy));
        }
        // https://felix.apache.org/documentation/subprojects/apache-felix-web-console/extending-the-apache-felix-web-console/providing-web-console-plugins.html
        String bundleLink = req.getAttribute("felix.webconsole.appRoot") + "/bundles/" + bundle.getBundleId();
        String pathEntriesString = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(PathEntry.getContentPaths(bundle), Spliterator.ORDERED),
                        false)
                .map(ContentLoaderWebConsolePlugin::printPathEntryTable)
                .collect(Collectors.joining("\n"));
        String trClass = (isEven ? "even" : "odd") + " ui-state-default";
        pw.printf(
                "<tr class='%s'><td><a href=\"%s\">%s (%d)</a></td><td>%s</td><td>%s<br/><br/>(%s)</td><td>%s</td></tr>",
                trClass,
                bundleLink,
                ResponseUtil.escapeXml(bundle.getSymbolicName()),
                bundle.getBundleId(),
                pathEntriesString,
                PropertiesUtil.toBoolean(
                        contentInfoMap.get(BundleContentLoaderListener.PROPERTY_CONTENT_LOADED), false),
                loadedDetails,
                uninstallPathsString);
    }

    static String printPathEntryTable(PathEntry entry) {
        StringBuilder sb = new StringBuilder();
        int row = 1;
        sb.append("<table class='nicetable nested'><thead>");
        sb.append("<tr><th>Path</th><th>")
                .append(ResponseUtil.escapeXml(entry.getPath()))
                .append("</th><tr>");
        sb.append("</thead><tbody>");
        printPathEntryTableRow(sb, "Target Path", ResponseUtil.escapeXml(entry.getTarget()), row++);
        // most important directives
        printPathEntryTableRow(sb, "Overwrite", Boolean.toString(entry.isOverwrite()), row++);
        printPathEntryTableRow(sb, "Uninstall", Boolean.toString(entry.isUninstall()), row++);
        printPathEntryTableRow(
                sb,
                "Ignored Content Readers",
                ResponseUtil.escapeXml(String.join(", ", entry.getIgnoredContentReaders())),
                row);
        sb.append("</tbody></table>");
        return sb.toString();
    }

    static void printPathEntryTableRow(StringBuilder sb, String name, String value, int i) {
        String trClass = (i % 2 == 0 ? "even" : "odd") + " ui-state-default";
        sb.append("<tr class='")
                .append(trClass)
                .append("'><td>")
                .append(name)
                .append("</td><td>")
                .append(value)
                .append("</td></tr>");
    }

    /**
     * Method to retrieve static resources from this bundle.
     */
    @SuppressWarnings("unused")
    private URL getResource(final String path) {
        if (path.startsWith("/" + RES_LOC)) {
            // strip label
            int index = path.indexOf('/', 1);
            if (index <= 0) {
                throw new IllegalStateException("The relativeResourcePrefix must contain at least one '/'");
            }
            return this.getClass().getResource(path.substring(index));
        }
        return null;
    }
}
