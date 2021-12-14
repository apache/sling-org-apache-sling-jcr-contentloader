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

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.lock.LockException;
import javax.jcr.lock.LockManager;

import org.apache.sling.commons.mime.MimeTypeService;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>BundleContentLoaderListener</code> is the service providing the
 * following functionality:
 * <ul>
 * <li>Bundle listener to load and unload initial content.
 * </ul>
 *
 */
@Component(service = {BundleHelper.class}, property = { Constants.SERVICE_VENDOR + "=The Apache Software Foundation",
        Constants.SERVICE_DESCRIPTION
                + "=Apache Sling Content Loader Implementation" }, immediate = true, configurationPolicy = ConfigurationPolicy.OPTIONAL)
@Designate(ocd = BundleContentLoaderConfiguration.class, factory = false)
public class BundleContentLoaderListener implements SynchronousBundleListener, BundleHelper {

    public static final String PROPERTY_CONTENT_LOADED = "content-loaded";
    public static final String PROPERTY_CONTENT_LOADED_AT = "content-load-time";
    protected static final String PROPERTY_CONTENT_LOADED_BY = "content-loaded-by";
    private static final String PROPERTY_CONTENT_UNLOADED_AT = "content-unload-time";
    private static final String PROPERTY_CONTENT_UNLOADED_BY = "content-unloaded-by";
    public static final String PROPERTY_UNINSTALL_PATHS = "uninstall-paths";

    public static final String BUNDLE_CONTENT_NODE = "/var/sling/bundle-content";

    /** default log */
    final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * SLING-10015 - To require a service user before becoming active
     */
    @Reference
    private ServiceUserMapped serviceUserMapped;

    /**
     * The JCR Repository we access to resolve resources
     */
    @Reference
    private SlingRepository repository;

    /**
     * The MimeTypeService used by the bundle content loader to resolve MIME types
     * for files to be installed.
     */
    @Reference
    private MimeTypeService mimeTypeService;

    /**
     * Service storing all available content readers.
     */
    @Reference
    private ContentReaderWhiteboard contentReaderWhiteboard;

    /**
     * The initial content loader which is called to load initial content up into
     * the repository when the providing bundle is installed.
     */
    private BundleContentLoader bundleContentLoader;

    /**
     * The id of the current instance
     */
    private String slingId;

    /**
     * List of currently updated bundles.
     */
    private final Set<String> updatedBundles = new HashSet<>();

    /** Sling settings service. */
    @Reference
    protected SlingSettingsService settingsService;

    // ---------- BundleListener -----------------------------------------------

    /**
     * Loads and unloads any content provided by the bundle whose state changed. If
     * the bundle has been started, the content is loaded. If the bundle is about to
     * stop, the content are unloaded.
     *
     * @param event The <code>BundleEvent</code> representing the bundle state
     *              change.
     */
    @Override
    public synchronized void bundleChanged(BundleEvent event) {

        //
        // NOTE:
        // This is synchronous - take care to not block the system !!
        //

        if (this.bundleContentLoader == null) {
            return;
        }

        Session session = null;
        final Bundle bundle = event.getBundle();
        switch (event.getType()) {
            case BundleEvent.RESOLVED:
                // register content when the bundle content is available
                // as node types are registered when the bundle is installed
                // we can safely add the content at this point.
                try {
                    session = this.getSession();
                    final boolean isUpdate;
                    isUpdate = this.updatedBundles.remove(bundle.getSymbolicName());
                    bundleContentLoader.registerBundle(session, bundle, isUpdate);
                } catch (Exception t) {
                    log.error("bundleChanged: Problem loading initial content of bundle " + bundle.getSymbolicName()
                            + " (" + bundle.getBundleId() + ")", t);
                } finally {
                    this.ungetSession(session);
                }
                break;
            case BundleEvent.UPDATED:
                // we just add the symbolic name to the list of updated bundles
                // we will use this info when the new start event is triggered
                this.updatedBundles.add(bundle.getSymbolicName());
                break;
            case BundleEvent.UNINSTALLED:
                try {
                    session = this.getSession();
                    bundleContentLoader.unregisterBundle(session, bundle);
                } catch (Exception t) {
                    log.error("bundleChanged: Problem unloading initial content of bundle " + bundle.getSymbolicName()
                            + " (" + bundle.getBundleId() + ")", t);
                } finally {
                    this.ungetSession(session);
                }
                break;
            default:
        }
    }

    // ---------- Implementation helpers --------------------------------------

    /** Returns the MIME type from the MimeTypeService for the given name */
    @Override
    public String getMimeType(String name) {
        // local copy to not get NPE despite check for null due to concurrent
        // unbind
        MimeTypeService mts = mimeTypeService;
        return (mts != null) ? mts.getMimeType(name) : null;
    }

    @Override
    public void createRepositoryPath(final Session writerSession, final String repositoryPath)
            throws RepositoryException {
        if (!writerSession.itemExists(repositoryPath)) {
            Node node = writerSession.getRootNode();
            String path = repositoryPath.substring(1);
            int pos = path.lastIndexOf('/');
            if (pos != -1) {
                final StringTokenizer st = new StringTokenizer(path.substring(0, pos), "/");
                while (st.hasMoreTokens()) {
                    final String token = st.nextToken();
                    if (!node.hasNode(token)) {
                        node.addNode(token, "sling:Folder");
                        writerSession.save();
                    }
                    node = node.getNode(token);
                }
                path = path.substring(pos + 1);
            }
            if (!node.hasNode(path)) {
                node.addNode(path, "sling:Folder");
                writerSession.save();
            }
        }
    }

    // ---------- SCR Integration ---------------------------------------------

    /** Activates this component, called by SCR before registering as a service */
    @Activate
    protected synchronized void activate(BundleContext bundleContext, BundleContentLoaderConfiguration configuration) {
        this.slingId = this.settingsService.getSlingId();
        this.bundleContentLoader = new BundleContentLoader(this, contentReaderWhiteboard, configuration);

        bundleContext.addBundleListener(this);

        Session session = null;
        try {
            session = this.getSession();
            this.createRepositoryPath(session, BUNDLE_CONTENT_NODE);
            log.debug("Activated - attempting to load content from all "
                    + "bundles which are neither INSTALLED nor UNINSTALLED");

            int ignored = 0;
            Bundle[] bundles = bundleContext.getBundles();
            for (Bundle bundle : bundles) {
                if ((bundle.getState() & (Bundle.INSTALLED | Bundle.UNINSTALLED)) == 0) {
                    // load content for bundles which are neither INSTALLED nor
                    // UNINSTALLED
                    loadBundle(bundle, session);
                } else {
                    ignored++;
                }

            }

            log.debug("Out of {} bundles, {} were not in a suitable state for initial content loading", bundles.length,
                    ignored);

        } catch (Exception t) {
            log.error("activate: Problem while loading initial content and"
                    + " registering mappings for existing bundles", t);
        } finally {
            this.ungetSession(session);
        }
    }

    private void loadBundle(Bundle bundle, Session session) throws RepositoryException {
        try {
            bundleContentLoader.registerBundle(session, bundle, false);
        } catch (Exception t) {
            log.error("Problem loading initial content of bundle " + bundle.getSymbolicName() + " ("
                    + bundle.getBundleId() + ")", t);
        } finally {
            if (session.hasPendingChanges()) {
                session.refresh(false);
            }
        }
    }

    /** Deactivates this component, called by SCR to take out of service */
    @Deactivate
    protected synchronized void deactivate(BundleContext bundleContext) {
        bundleContext.removeBundleListener(this);

        if (this.bundleContentLoader != null) {
            this.bundleContentLoader.dispose();
            this.bundleContentLoader = null;
        }
    }

    // ---------- internal helper ----------------------------------------------

    /** Returns the JCR repository used by this service. */
    protected SlingRepository getRepository() {
        return repository;
    }

    /**
     * Returns an administrative session to the default workspace.
     */
    @Override
    public Session getSession() throws RepositoryException {
        return getRepository().loginService(null, null);
    }

    /**
     * Returns an administrative session for the named workspace.
     */
    @Override
    public Session getSession(final String workspace) throws RepositoryException {
        return getRepository().loginService(null, workspace);
    }

    /**
     * Return the administrative session and close it.
     */
    private void ungetSession(final Session session) {
        if (session != null) {
            try {
                session.logout();
            } catch (Exception t) {
                log.error("Unable to log out of session: " + t.getMessage(), t);
            }
        }
    }

    /**
     * Return the bundle content info and make an exclusive lock.
     * 
     * @param session
     * @param bundle
     * @return The map of bundle content info or null.
     * @throws RepositoryException
     */
    @Override
    public Map<String, Object> getBundleContentInfo(final Session session, final Bundle bundle, boolean create)
            throws RepositoryException {
        final String nodeName = bundle.getSymbolicName();
        final Node parentNode = (Node) session.getItem(BUNDLE_CONTENT_NODE);
        if (!parentNode.hasNode(nodeName)) {
            if (!create) {
                return null;
            }
            try {
                final Node bcNode = parentNode.addNode(nodeName, "nt:unstructured");
                bcNode.addMixin("mix:lockable");
                session.save();
            } catch (RepositoryException re) {
                // for concurrency issues (running in a cluster) we ignore exceptions
                this.log.warn("Unable to create node " + nodeName, re);
                session.refresh(true);
            }
        }
        final Node bcNode = parentNode.getNode(nodeName);
        if (bcNode.isLocked()) {
            return null;
        }
        try {
            LockManager lockManager = session.getWorkspace().getLockManager();
            lockManager.lock(bcNode.getPath(), false, // isDeep
                    true, // isSessionScoped
                    Long.MAX_VALUE, // timeoutHint
                    null); // ownerInfo
        } catch (LockException le) {
            return null;
        }
        final Map<String, Object> info = new HashMap<>();
        if (bcNode.hasProperty(PROPERTY_CONTENT_LOADED_AT)) {
            info.put(PROPERTY_CONTENT_LOADED_AT, bcNode.getProperty(PROPERTY_CONTENT_LOADED_AT).getDate());
        }
        if (bcNode.hasProperty(PROPERTY_CONTENT_LOADED)) {
            info.put(PROPERTY_CONTENT_LOADED, bcNode.getProperty(PROPERTY_CONTENT_LOADED).getBoolean());
        } else {
            info.put(PROPERTY_CONTENT_LOADED, false);
        }
        if (bcNode.hasProperty(PROPERTY_CONTENT_LOADED_BY)) {
            info.put(PROPERTY_CONTENT_LOADED_BY, bcNode.getProperty(PROPERTY_CONTENT_LOADED_BY).getString());
        }
        if (bcNode.hasProperty(PROPERTY_UNINSTALL_PATHS)) {
            final Value[] values = bcNode.getProperty(PROPERTY_UNINSTALL_PATHS).getValues();
            final String[] s = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                s[i] = values[i].getString();
            }
            info.put(PROPERTY_UNINSTALL_PATHS, s);
        }
        return info;
    }

    @Override
    public void unlockBundleContentInfo(final Session session, final Bundle bundle, final boolean contentLoaded,
            final List<String> createdNodes) throws RepositoryException {
        final String nodeName = bundle.getSymbolicName();
        final Node parentNode = (Node) session.getItem(BUNDLE_CONTENT_NODE);
        final Node bcNode = parentNode.getNode(nodeName);
        if (contentLoaded) {
            bcNode.setProperty(PROPERTY_CONTENT_LOADED, contentLoaded);
            bcNode.setProperty(PROPERTY_CONTENT_LOADED_AT, Calendar.getInstance());
            bcNode.setProperty(PROPERTY_CONTENT_LOADED_BY, this.slingId);
            bcNode.setProperty(PROPERTY_CONTENT_UNLOADED_AT, (String) null);
            bcNode.setProperty(PROPERTY_CONTENT_UNLOADED_BY, (String) null);
            if (createdNodes != null && !createdNodes.isEmpty()) {
                bcNode.setProperty(PROPERTY_UNINSTALL_PATHS, createdNodes.toArray(new String[createdNodes.size()]));
            }
            session.save();
        }
        LockManager lockManager = session.getWorkspace().getLockManager();
        lockManager.unlock(bcNode.getPath());
    }

    @Override
    public void contentIsUninstalled(final Session session, final Bundle bundle) {
        final String nodeName = bundle.getSymbolicName();
        try {
            final Node parentNode = (Node) session.getItem(BUNDLE_CONTENT_NODE);
            if (parentNode.hasNode(nodeName)) {
                final Node bcNode = parentNode.getNode(nodeName);
                bcNode.setProperty(PROPERTY_CONTENT_LOADED, false);
                bcNode.setProperty(PROPERTY_CONTENT_UNLOADED_AT, Calendar.getInstance());
                bcNode.setProperty(PROPERTY_CONTENT_UNLOADED_BY, this.slingId);
                bcNode.setProperty(PROPERTY_UNINSTALL_PATHS, (String[]) null);
                session.save();
            }
        } catch (RepositoryException re) {
            this.log.error("Unable to update bundle content info.", re);
        }
    }
}
