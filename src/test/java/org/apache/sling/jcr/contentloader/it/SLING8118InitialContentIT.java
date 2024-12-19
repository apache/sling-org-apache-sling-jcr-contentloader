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
package org.apache.sling.jcr.contentloader.it;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.Privilege;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimap;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlEntry;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.jcr.base.util.AccessControlUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.Bundle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.factoryConfiguration;

/**
 * test of a bundle that provides initial content that defines an ace with restrictions
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class SLING8118InitialContentIT extends ContentloaderTestSupport {

    @Configuration
    public Option[] configuration() throws IOException {
        final String header = DEFAULT_PATH_IN_BUNDLE + ";path:=" + CONTENT_ROOT_PATH;
        final Multimap<String, String> content = ImmutableListMultimap.of(DEFAULT_PATH_IN_BUNDLE, "SLING-8118.json");
        final Option bundle = buildInitialContentBundle(header, content);
        // configure the health check component
        Option hcConfig = factoryConfiguration("org.apache.sling.jcr.contentloader.hc.BundleContentLoadedCheck")
                .put("hc.tags", new String[] {TAG_TESTING_CONTENT_LOADING})
                .asOption();
        return new Option[] {baseConfiguration(), hcConfig, bundle};
    }

    /* (non-Javadoc)
     * @see org.apache.sling.jcr.contentloader.it.ContentloaderTestSupport#setup()
     */
    @Before
    @Override
    public void setup() throws Exception {
        super.setup();

        waitForContentLoaded();
    }

    @Test
    public void bundleStarted() {
        final Bundle b = findBundle(BUNDLE_SYMBOLICNAME);
        assertNotNull("Expecting bundle to be found:" + BUNDLE_SYMBOLICNAME, b);
        assertEquals("Expecting bundle to be active:" + BUNDLE_SYMBOLICNAME, Bundle.ACTIVE, b.getState());
    }

    @Test
    public void initialContentInstalled() throws RepositoryException {
        final String folderPath = CONTENT_ROOT_PATH + "/SLING-8118";
        assertTrue("Expecting initial content to be installed", session.itemExists(folderPath));
        assertEquals(
                "folder has node type 'sling:Folder'",
                "sling:Folder",
                session.getNode(folderPath).getPrimaryNodeType().getName());
    }

    @Test
    public void userCreated() throws RepositoryException {
        UserManager userManager = AccessControlUtil.getUserManager(session);
        Authorizable authorizable = userManager.getAuthorizable("sling8118_user");
        assertNotNull("Expecting test user to exist", authorizable);
    }

    @Test
    public void groupCreated() throws RepositoryException {
        UserManager userManager = AccessControlUtil.getUserManager(session);
        Authorizable authorizable = userManager.getAuthorizable("sling8118_group");
        assertNotNull("Expecting test group to exist", authorizable);
        assertTrue(authorizable instanceof Group);
        Iterator<Authorizable> members = ((Group) authorizable).getMembers();
        assertTrue(members.hasNext());
        Authorizable firstMember = members.next();
        assertEquals("sling8118_user", firstMember.getID());
    }

    @Test
    public void aceWithRestrictionsCreated() throws RepositoryException {
        final String folderPath = CONTENT_ROOT_PATH + "/SLING-8118";
        assertTrue("Expecting test folder to exist", session.itemExists(folderPath));

        AccessControlManager accessControlManager = AccessControlUtil.getAccessControlManager(session);
        AccessControlPolicy[] policies = accessControlManager.getPolicies(folderPath);
        List<AccessControlEntry> allEntries = new ArrayList<AccessControlEntry>();
        for (AccessControlPolicy accessControlPolicy : policies) {
            if (accessControlPolicy instanceof AccessControlList) {
                AccessControlEntry[] accessControlEntries =
                        ((AccessControlList) accessControlPolicy).getAccessControlEntries();
                allEntries.addAll(Arrays.asList(accessControlEntries));
            }
        }
        assertEquals(3, allEntries.size());
        Map<String, AccessControlEntry> aceMap = new HashMap<>();
        for (AccessControlEntry accessControlEntry : allEntries) {
            aceMap.put(accessControlEntry.getPrincipal().getName(), accessControlEntry);
        }

        // check ACE for sling8118_user
        AccessControlEntry testUserAce = aceMap.get("sling8118_user");
        assertNotNull("Expected ACE for test user", testUserAce);
        assertEquals("sling8118_user", testUserAce.getPrincipal().getName());
        Privilege[] privileges = testUserAce.getPrivileges();
        assertNotNull(privileges);
        assertEquals(2, privileges.length);
        Set<String> privilegeNames = new HashSet<>();
        for (Privilege privilege : privileges) {
            privilegeNames.add(privilege.getName());
        }
        assertTrue("Expecting granted read privilege", privilegeNames.contains("jcr:read"));
        assertTrue("Expecting granted write privilege", privilegeNames.contains("jcr:write"));

        // check restrictions
        assertTrue(testUserAce instanceof JackrabbitAccessControlEntry);
        JackrabbitAccessControlEntry testUserJAce = (JackrabbitAccessControlEntry) testUserAce;
        String[] testUserRestrictionNames = testUserJAce.getRestrictionNames();
        assertNotNull(testUserRestrictionNames);
        assertEquals(1, testUserRestrictionNames.length);
        assertEquals("rep:glob", testUserRestrictionNames[0]);
        Value globRestriction = testUserJAce.getRestriction("rep:glob");
        assertNotNull(globRestriction);
        assertEquals("glob1", globRestriction.getString());

        // check ACE for sling8118_group
        AccessControlEntry testGroupAce = aceMap.get("sling8118_group");
        assertNotNull("Expected ACE for test user", testGroupAce);
        assertEquals("sling8118_group", testGroupAce.getPrincipal().getName());
        privileges = testGroupAce.getPrivileges();
        assertNotNull(privileges);
        assertEquals(1, privileges.length);
        privilegeNames = new HashSet<>();
        for (Privilege privilege : privileges) {
            privilegeNames.add(privilege.getName());
        }
        assertTrue(
                "Expecting granted modifyAccessControl privilege", privilegeNames.contains("jcr:modifyAccessControl"));

        // check restrictions
        assertTrue(testGroupAce instanceof JackrabbitAccessControlEntry);
        JackrabbitAccessControlEntry testGroupJAce = (JackrabbitAccessControlEntry) testGroupAce;
        String[] testGroupRestrictionNames = testGroupJAce.getRestrictionNames();
        assertNotNull(testGroupRestrictionNames);
        assertEquals(1, testGroupRestrictionNames.length);
        assertEquals("rep:itemNames", testGroupRestrictionNames[0]);
        Value[] restrictions = testGroupJAce.getRestrictions("rep:itemNames");
        assertNotNull(restrictions);
        assertEquals(2, restrictions.length);
        assertEquals("name1", restrictions[0].getString());
        assertEquals("name2", restrictions[1].getString());

        // check ACE for everyone group
        AccessControlEntry everyoneAce = aceMap.get("everyone");
        assertNotNull("Expected ACE for everyone", everyoneAce);
        assertEquals("everyone", everyoneAce.getPrincipal().getName());
        privileges = everyoneAce.getPrivileges();
        assertNotNull(privileges);
        assertEquals(1, privileges.length);

        assertEquals("Expecting granted read privilege", "jcr:read", privileges[0].getName());

        // check restrictions
        assertTrue(everyoneAce instanceof JackrabbitAccessControlEntry);
        JackrabbitAccessControlEntry everyoneJAce = (JackrabbitAccessControlEntry) everyoneAce;
        String[] everyoneRestrictionNames = everyoneJAce.getRestrictionNames();
        assertNotNull(everyoneRestrictionNames);
        assertEquals(0, everyoneRestrictionNames.length);
    }
}
