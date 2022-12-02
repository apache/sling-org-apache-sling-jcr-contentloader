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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.vmOption;
import static org.ops4j.pax.exam.cm.ConfigurationAdminOptions.factoryConfiguration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.Privilege;

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
import org.ops4j.pax.exam.options.ModifiableCompositeOption;
import org.ops4j.pax.exam.options.extra.VMOption;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.Bundle;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimap;

/**
 * SLING-11713 test ACL json input structure to be less ambiguous for restrictions
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class SLING11713InitialContentIT extends ContentloaderTestSupport {

    @Configuration
    public Option[] configuration() throws IOException {
        final String header = DEFAULT_PATH_IN_BUNDLE + ";path:=" + CONTENT_ROOT_PATH;
        final Multimap<String, String> content = ImmutableListMultimap.of(
            DEFAULT_PATH_IN_BUNDLE, "SLING-11713.json"
        );
        final Option bundle = buildInitialContentBundle(header, content);
        // configure the health check component
        Option hcConfig = factoryConfiguration("org.apache.sling.jcr.contentloader.hc.BundleContentLoadedCheck")
            .put("hc.tags", new String[] {TAG_TESTING_CONTENT_LOADING})
            .asOption();
        return new Option[]{
            baseConfiguration(),
            hcConfig,
            bundle,
            optionalRemoteDebug()
        };
    }

    /**
     * Optionally configure remote debugging on the port supplied by the "debugPort"
     * system property.
     */
    protected ModifiableCompositeOption optionalRemoteDebug() {
        VMOption option = null;
        String property = System.getProperty("debugPort");
        if (property != null) {
            option = vmOption(String.format("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=%s", property));
        }
        return composite(option);
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
        final String folderPath = CONTENT_ROOT_PATH + "/SLING-11713";
        assertTrue("Expecting initial content to be installed", session.itemExists(folderPath));
        assertEquals("folder has node type 'sling:Folder'", "sling:Folder", session.getNode(folderPath).getPrimaryNodeType().getName());
    }

    @Test
    public void userCreated() throws RepositoryException {
        UserManager userManager = AccessControlUtil.getUserManager(session);
        Authorizable authorizable = userManager.getAuthorizable("sling11713_user");
        assertNotNull("Expecting test user to exist", authorizable);
    }

    @Test
    public void groupCreated() throws RepositoryException {
        UserManager userManager = AccessControlUtil.getUserManager(session);
        Authorizable authorizable = userManager.getAuthorizable("sling11713_group");
        assertNotNull("Expecting test group to exist", authorizable);
        assertTrue(authorizable instanceof Group);
        Iterator<Authorizable> members = ((Group) authorizable).getMembers();
        assertTrue(members.hasNext());
        Authorizable firstMember = members.next();
        assertEquals("sling11713_user", firstMember.getID());
    }

    @Test
    public void aceWithRestrictionsCreated() throws RepositoryException {
        final String folderPath = CONTENT_ROOT_PATH + "/SLING-11713";
        assertTrue("Expecting test folder to exist", session.itemExists(folderPath));

        AccessControlManager accessControlManager = AccessControlUtil.getAccessControlManager(session);
        AccessControlPolicy[] policies = accessControlManager.getPolicies(folderPath);
        List<AccessControlEntry> allEntries = new ArrayList<AccessControlEntry>();
        for (AccessControlPolicy accessControlPolicy : policies) {
            if (accessControlPolicy instanceof AccessControlList) {
                AccessControlEntry[] accessControlEntries = ((AccessControlList) accessControlPolicy).getAccessControlEntries();
                allEntries.addAll(Arrays.asList(accessControlEntries));
            }
        }
        assertEquals(9, allEntries.size());
        Map<String, List<AccessControlEntry>> aceMap = new HashMap<>();
        for (AccessControlEntry accessControlEntry : allEntries) {
            List<AccessControlEntry> aceList = aceMap.computeIfAbsent(accessControlEntry.getPrincipal().getName(), name -> new ArrayList<>());
            aceList.add(accessControlEntry);
        }

        //check ACE for sling11713_user
        List<AccessControlEntry> aceList = aceMap.get("sling11713_user");
        assertNotNull(aceList);
        assertEquals(3, aceList.size());
        assertAce(aceList.get(0), "sling11713_user",
                false, // isAllow
                new String[] {"jcr:write"}, // PrivilegeNames
                new String[] {"rep:glob"}, // RestrictionNames
                new String[][] {new String[]{"glob1deny"}}); // RestrictionValues
        assertAce(aceList.get(1), "sling11713_user",
                true, // isAllow
                new String[] {"jcr:read"}, // PrivilegeNames
                new String[] {}, // RestrictionNames
                new String[][] {}); // RestrictionValues
        assertAce(aceList.get(2), "sling11713_user",
                true, // isAllow
                new String[] {"jcr:write"}, // PrivilegeNames
                new String[] {"rep:glob"}, // RestrictionNames
                new String[][] {new String[]{"glob1allow"}}); // RestrictionValues

        //check ACE for sling11713_group
        aceList = aceMap.get("sling11713_group");
        assertNotNull(aceList);
        assertEquals(1, aceList.size());
        assertAce(aceList.get(0), "sling11713_group",
                true, // isAllow
                new String[] {"jcr:modifyAccessControl"}, // PrivilegeNames
                new String[] {"rep:itemNames"}, // RestrictionNames
                new String[][] {new String[]{"name1", "name2"}}); // RestrictionValues

        //check ACE for everyone
        aceList = aceMap.get("everyone");
        assertNotNull(aceList);
        assertEquals(3, aceList.size());
        assertAce(aceList.get(0), "everyone",
                false, // isAllow
                new String[] {"jcr:write"}, // PrivilegeNames
                new String[] {"rep:glob"}, // RestrictionNames
                new String[][] {new String[]{"glob1deny"}}); // RestrictionValues
        assertAce(aceList.get(1), "everyone",
                true, // isAllow
                new String[] {"jcr:read"}, // PrivilegeNames
                new String[] {}, // RestrictionNames
                new String[][] {}); // RestrictionValues
        assertAce(aceList.get(2), "everyone",
                true, // isAllow
                new String[] {"jcr:write"}, // PrivilegeNames
                new String[] {"rep:glob"}, // RestrictionNames
                new String[][] {new String[]{"glob1allow"}}); // RestrictionValues

        aceList = aceMap.get("sling11713_user2");
        assertNotNull(aceList);
        assertEquals(2, aceList.size());
        assertAce(aceList.get(0), "sling11713_user2",
                false, // isAllow
                new String[] {"jcr:read"}, // PrivilegeNames
                new String[] {"rep:itemNames"}, // RestrictionNames
                new String[][] {new String[]{"name1"}}); // RestrictionValues
        assertAce(aceList.get(1), "sling11713_user2",
                true, // isAllow
                new String[] {"jcr:read"}, // PrivilegeNames
                new String[] {"rep:glob"}, // RestrictionNames
                new String[][] {new String[]{"glob1allow"}}); // RestrictionValues
    }

    public static void assertAce(AccessControlEntry ace, String expectedPrincipal,
            boolean isAllow, String[] expectedPrivilegeNames,
            String[] expectedRestrictionNames, String[][] expectedRestrictionValues) throws RepositoryException {

        assertNotNull("Expected ACE for test principal", expectedPrincipal);
        assertEquals(expectedPrincipal, ace.getPrincipal().getName());

        Privilege[] storedPrivileges = ace.getPrivileges();
        assertNotNull(storedPrivileges);
        assertEquals(expectedPrivilegeNames.length, storedPrivileges.length);
        Set<String> privilegeNamesSet = Stream.of(storedPrivileges)
            .map(item -> item.getName())
            .collect(Collectors.toSet());
        for (String pn : expectedPrivilegeNames) {
            assertTrue("Expecting privilege: " + pn, privilegeNamesSet.contains(pn));
        }

        assertTrue(ace instanceof JackrabbitAccessControlEntry);
        JackrabbitAccessControlEntry jace = (JackrabbitAccessControlEntry) ace;
        assertEquals(isAllow, jace.isAllow());

        //check restrictions
        String[] storedRestrictionNames = jace.getRestrictionNames();
        assertNotNull(storedRestrictionNames);
        assertEquals(expectedRestrictionNames.length, storedRestrictionNames.length);
        Set<String> restrictionNamesSet = Stream.of(storedRestrictionNames)
            .collect(Collectors.toSet());
        for (String rn : expectedRestrictionNames) {
            assertTrue("Expecting restriction: " + rn, restrictionNamesSet.contains(rn));
        }

        if (expectedRestrictionValues.length > 0) {
            for (int i = 0; i < expectedRestrictionValues.length; i++) {
                String[] expected = expectedRestrictionValues[i];
                Value[] storedRestrictionValues = jace.getRestrictions(storedRestrictionNames[i]);
                assertNotNull(storedRestrictionValues);
                assertEquals(expected.length, storedRestrictionValues.length);
                Set<String> restrictionValuesSet = Stream.of(storedRestrictionValues)
                        .map(item -> {
                            try {
                                return item.getString();
                            } catch (IllegalStateException | RepositoryException e) {
                                // should never get here
                                return null;
                            }
                        })
                        .collect(Collectors.toSet());
                for (String rv : expected) {
                    assertTrue("Expecting restriction value: " + rv, restrictionValuesSet.contains(rv));
                }
            }
        }
    }
}
