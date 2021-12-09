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

import static java.util.Collections.singletonMap;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.lang.annotation.Annotation;
import java.security.Principal;

import javax.jcr.AccessDeniedException;
import javax.jcr.NamespaceException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.nodetype.NodeType;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.JackrabbitWorkspace;
import org.apache.jackrabbit.api.security.authorization.PrivilegeManager;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.jackrabbit.oak.spi.security.principal.EveryonePrincipal;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.jcr.contentloader.internal.readers.JsonReader;
import org.apache.sling.jcr.contentloader.internal.readers.XmlReader;
import org.apache.sling.jcr.contentloader.internal.readers.ZipReader;
import org.apache.sling.jcr.resource.internal.helper.JcrResourceUtil;
import org.apache.sling.testing.mock.osgi.MockBundle;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.osgi.framework.Bundle;

public class BundleContentLoaderTest {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private BundleContentLoaderListener bundleHelper;

    private ContentReaderWhiteboard whiteboard;

    private static final String CUSTOM_PRIVILEGE_NAME = "customPrivilege";
    @Before
    public void prepareContentLoader() throws Exception {
        // prepare content readers
        context.registerInjectActivateService(new JsonReader());
        context.registerInjectActivateService(new XmlReader());
        context.registerInjectActivateService(new ZipReader());

        // whiteboard which holds readers
        context.registerInjectActivateService(new ContentReaderWhiteboard());

        // register the content loader service
        bundleHelper = context.registerInjectActivateService(new BundleContentLoaderListener());

        whiteboard = context.getService(ContentReaderWhiteboard.class);

    }

    private static Privilege getOrRegisterCustomPrivilege(Session session) throws AccessDeniedException, NamespaceException, RepositoryException {
        // register custom privilege
        Workspace wsp = session.getWorkspace();
        if (!(wsp instanceof JackrabbitWorkspace)) {
            throw new RepositoryException("Unable to register privileges. No JackrabbitWorkspace.");
        }
        PrivilegeManager mgr = ((JackrabbitWorkspace) wsp).getPrivilegeManager();
        try {
            return mgr.getPrivilege(CUSTOM_PRIVILEGE_NAME);
        } catch (AccessControlException e) {
            return mgr.registerPrivilege(CUSTOM_PRIVILEGE_NAME, false, new String[0]);
        }
    }

    private AccessControlEntry[] createFolderNodeAndACL(String path) throws RepositoryException {
        // use JCR API to add some node and acl
        Session session = context.resourceResolver().adaptTo(Session.class);
        Privilege customPrivilege = getOrRegisterCustomPrivilege(session);
        Privilege[] customPrivilegeSingleItemArray = new Privilege[]{ customPrivilege };

        JcrUtils.getOrCreateByPath(path, NodeType.NT_FOLDER, session);
        
        AccessControlManager acMgr = session.getAccessControlManager();
        AccessControlList acl = (AccessControlList)acMgr.getApplicablePolicies(path).nextAccessControlPolicy();
        Principal everyone = EveryonePrincipal.getInstance();
        assertTrue(acl.addAccessControlEntry(everyone, customPrivilegeSingleItemArray));
        AccessControlEntry[] expectedAces = acl.getAccessControlEntries();
        acMgr.setPolicy(path, acl);
        
        session.save();
        return expectedAces;
    }

    private void assertFolderNodeAndACL(String path, AccessControlEntry[] expectedAces) throws RepositoryException {
        Session session = context.resourceResolver().adaptTo(Session.class);
        session.refresh(false);
        assertTrue(session.nodeExists(path));
        assertEquals(JcrConstants.NT_FOLDER, session.getNode(path).getPrimaryNodeType().getName());
        AccessControlManager acMgr = session.getAccessControlManager();
        AccessControlList acl = (AccessControlList)acMgr.getPolicies(path)[0];
        AccessControlEntry[] aces = acl.getAccessControlEntries();
        MatcherAssert.assertThat(aces, Matchers.arrayContaining(expectedAces));
    }
 
    @Test
    public void loadContentOverwriteWithoutPath() throws Exception {
        AccessControlEntry[] expectedAces = createFolderNodeAndACL("/apps/child");
        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);
        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF2;overwrite:=true");
        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);
        Resource imported = context.resourceResolver().getResource("/apps/sling/validation/content");
        assertNull("Resource was unexpectedly imported", imported);
        assertFolderNodeAndACL("/apps/child", expectedAces);
    }

    @Test
    public void loadContentOverwriteWithRootPath() throws Exception {
        AccessControlEntry[] expectedAces = createFolderNodeAndACL("/apps/child");
        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);
        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF2;overwrite:=true;path:=/");
        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);
        Resource imported = context.resourceResolver().getResource("/apps/sling/validation/content");
        assertNull("Resource was unexpectedly imported", imported);
        assertFolderNodeAndACL("/apps/child", expectedAces);
    }

    @Test
    public void loadContentOverwriteWith2ndLevelPath() throws Exception {
        AccessControlEntry[] expectedAces = createFolderNodeAndACL("/apps/child");
        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);
        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF2/apps;overwrite:=true;path:=/apps");
        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);
        Resource imported = context.resourceResolver().getResource("/apps/sling/validation/content");

        assertThat("Resource was not imported", imported, notNullValue());
        assertThat("sling:resourceType was not properly set", imported.getResourceType(), equalTo("sling:Folder"));
        assertNull(context.resourceResolver().getResource("/apps/child"));
    }

    @Test
    public void loadContentWithSpecificPath() throws Exception {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);

        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF/libs/app;path:=/libs/app");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        Resource imported = context.resourceResolver().getResource("/libs/app");

        assertThat("Resource was not imported", imported, notNullValue());
        assertThat("sling:resourceType was not properly set", imported.getResourceType(), equalTo("sling:Folder"));
    }

    @Test
    public void loadContentWithExcludes() throws Exception {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard,
                new BundleContentLoaderConfiguration() {
                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return null;
                    }

                    @Override
                    public String[] includedTargets() {
                        return new String[] { "^/.*$" };
                    }

                    @Override
                    public String[] excludedTargets() {
                        return new String[] { "^/libs.*$" };
                    }

                });

        Bundle mockBundle = newBundleWithInitialContent(context, 
                "SLING-INF/libs/app;path:=/libs/app,SLING-INF/content/app;path:=/content/app");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        assertThat("Excluded resource imported", context.resourceResolver().getResource("/libs/app"), nullValue());
    }


    @Test
    public void loadContentWithNullValue() throws Exception {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard,
                new BundleContentLoaderConfiguration() {
                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return null;
                    }

                    @Override
                    public String[] includedTargets() {
                        return new String[] { "^/.*$" };
                    }

                    @Override
                    public String[] excludedTargets() {
                        return null;
                    }

                });

        Bundle mockBundle = newBundleWithInitialContent(context,
                "SLING-INF/libs/app;path:=/libs/app,SLING-INF/content/app;path:=/content/app");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        assertThat("Excluded resource imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
    }


    @Test
    public void loadContentWithIncludes() throws Exception {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard,
                new BundleContentLoaderConfiguration() {
                    @Override
                    public Class<? extends Annotation> annotationType() {
                        return null;
                    }

                    @Override
                    public String[] includedTargets() {
                        return new String[] { "^/.*$" };
                    }

                    @Override
                    public String[] excludedTargets() {
                        return new String[] { "^/app.*$" };
                    }

                });

        Bundle mockBundle = newBundleWithInitialContent(context, 
                "SLING-INF/libs/app;path:=/libs/app");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        assertThat("Included resource not imported", context.resourceResolver().getResource("/libs/app"), notNullValue());
    }

    @Test
    public void loadContentWithRootPath() throws Exception {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);

        Bundle mockBundle = newBundleWithInitialContent(context, "SLING-INF/");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        Resource imported = context.resourceResolver().getResource("/libs/app");

        assertThat("Resource was not imported", imported, notNullValue());
        assertThat("sling:resourceType was not properly set", imported.getResourceType(), equalTo("sling:Folder"));
    }

    @Test
    @Ignore("TODO - unregister or somehow ignore the XmlReader component for this test")
    public void loadXmlAsIs() throws Exception {

        BundleContentLoader contentLoader = new BundleContentLoader(bundleHelper, whiteboard, null);

        dumpRepo("/", 2);

        Bundle mockBundle = newBundleWithInitialContent(context, 
                "SLING-INF/libs/app;path:=/libs/app;ignoreImportProviders:=xml");

        contentLoader.registerBundle(context.resourceResolver().adaptTo(Session.class), mockBundle, false);

        Resource imported = context.resourceResolver().getResource("/libs/app");

        assertThat("Resource was not imported", imported, notNullValue());
        assertThat("sling:resourceType was not properly set", imported.getResourceType(), equalTo("sling:Folder"));

        Resource xmlFile = context.resourceResolver().getResource("/libs/app.xml");

        dumpRepo("/", 2);

        assertThat("XML file was was not imported", xmlFile, notNullValue());

    }

    public static MockBundle newBundleWithInitialContent(SlingContext context, String initialContentHeader) {
        MockBundle mockBundle = new MockBundle(context.bundleContext());
        mockBundle.setHeaders(singletonMap("Sling-Initial-Content", initialContentHeader));
        return mockBundle;
    }

    private void dumpRepo(String startPath, int maxDepth) {

        dumpRepo0(startPath, maxDepth, 0);
    }

    private void dumpRepo0(String startPath, int maxDepth, int currentDepth) {
        Resource resource = context.resourceResolver().getResource(startPath);
        StringBuilder format = new StringBuilder();
        for (int i = 0; i < currentDepth; i++) {
            format.append("  ");
        }
        format.append("%s [%s]%n");
        String name = resource.getName().length() == 0 ? "/" : resource.getName();
        System.out.format(format.toString(), name, resource.getResourceType());
        currentDepth++;
        if (currentDepth > maxDepth) {
            return;
        }
        for (Resource child : resource.getChildren()) {
            dumpRepo0(child.getPath(), maxDepth, currentDepth);
        }
    }
}
