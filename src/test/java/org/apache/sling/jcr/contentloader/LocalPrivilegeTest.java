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

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.jackrabbit.oak.security.authorization.restriction.RestrictionProviderImpl;
import org.apache.jackrabbit.oak.spi.security.authorization.accesscontrol.AccessControlConstants;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeConstants;
import org.apache.jackrabbit.value.ValueFactoryImpl;
import org.apache.sling.jcr.base.util.AccessControlUtil;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 *
 */
public class LocalPrivilegeTest {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private AccessControlManager acm;

    @Before
    public void setup() throws RepositoryException {
        Session session = context.resourceResolver().adaptTo(Session.class);
        acm = AccessControlUtil.getAccessControlManager(session);
        context.registerService(new RestrictionProviderImpl());
    }

    private Privilege priv(String privilegeName) throws RepositoryException {
        return acm.privilegeFromName(privilegeName);
    }

    private Value val(String value) {
        return ValueFactoryImpl.getInstance().createValue(value);
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#hashCode()}.
     */
    @Test
    public void testHashCode() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        LocalPrivilege lp2 = new LocalPrivilege(PrivilegeConstants.JCR_WRITE);
        assertNotEquals(lp1.hashCode(), lp2.hashCode());

        LocalPrivilege lp3 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertEquals(lp1.hashCode(), lp3.hashCode());

        LocalPrivilege lp4 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp4.setAllow(true);
        assertNotEquals(lp1.hashCode(), lp4.hashCode());
        lp4.setDeny(true);
        assertNotEquals(lp1.hashCode(), lp4.hashCode());
        lp4.setAllowRestrictions(null);
        assertNotEquals(lp1.hashCode(), lp4.hashCode());
        lp4.setDenyRestrictions(null);
        assertNotEquals(lp1.hashCode(), lp4.hashCode());

        LocalPrivilege lp5 = new LocalPrivilege(null);
        assertNotEquals(lp1.hashCode(), lp5.hashCode());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#checkPrivilege(javax.jcr.security.AccessControlManager)}.
     * @throws RepositoryException
     */
    @Test
    public void testCheckPrivilege() throws RepositoryException {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp1.checkPrivilege(acm);
        assertNotNull(lp1.getPrivilege());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#getPrivilege()}.
     * @throws RepositoryException
     */
    @Test
    public void testGetPrivilege() throws RepositoryException {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp1.checkPrivilege(acm);
        assertEquals(priv(PrivilegeConstants.JCR_READ), lp1.getPrivilege());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#getName()}.
     */
    @Test
    public void testGetName() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertEquals(PrivilegeConstants.JCR_READ, lp1.getName());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#isAllow()}.
     */
    @Test
    public void testIsAllow() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertFalse(lp1.isAllow());

        lp1.setAllow(true);
        assertTrue(lp1.isAllow());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#isDeny()}.
     */
    @Test
    public void testIsDeny() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertFalse(lp1.isDeny());

        lp1.setDeny(true);
        assertTrue(lp1.isDeny());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#setAllow(boolean)}.
     */
    @Test
    public void testSetAllow() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp1.setAllow(true);
        assertTrue(lp1.isAllow());
        lp1.setAllow(false);
        assertFalse(lp1.isAllow());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#setDeny(boolean)}.
     */
    @Test
    public void testSetDeny() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp1.setDeny(true);
        assertTrue(lp1.isDeny());
        lp1.setDeny(false);
        assertFalse(lp1.isDeny());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#getAllowRestrictions()}.
     */
    @Test
    public void testGetAllowRestrictions() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        Set<LocalRestriction> allowRestrictions = lp1.getAllowRestrictions();
        assertNotNull(allowRestrictions);
        assertTrue(allowRestrictions.isEmpty());

        Set<LocalRestriction> newAllowRestrictions = new HashSet<>();
        newAllowRestrictions.add(new LocalRestriction(AccessControlConstants.REP_GLOB, val("/hello")));
        lp1.setAllowRestrictions(newAllowRestrictions);
        Set<LocalRestriction> allowRestrictions2 = lp1.getAllowRestrictions();
        assertNotNull(allowRestrictions2);
        assertFalse(allowRestrictions2.isEmpty());
        assertEquals(newAllowRestrictions, allowRestrictions2);
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#setAllowRestrictions(java.util.Set)}.
     */
    @Test
    public void testSetAllowRestrictions() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertTrue(lp1.getAllowRestrictions().isEmpty());

        Set<LocalRestriction> newAllowRestrictions = new HashSet<>();
        newAllowRestrictions.add(new LocalRestriction(AccessControlConstants.REP_GLOB, val("/hello")));
        lp1.setAllowRestrictions(newAllowRestrictions);
        assertEquals(newAllowRestrictions, lp1.getAllowRestrictions());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#getDenyRestrictions()}.
     */
    @Test
    public void testGetDenyRestrictions() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        Set<LocalRestriction> denyRestrictions = lp1.getDenyRestrictions();
        assertNotNull(denyRestrictions);
        assertTrue(denyRestrictions.isEmpty());

        Set<LocalRestriction> newDenyRestrictions = new HashSet<>();
        newDenyRestrictions.add(new LocalRestriction(AccessControlConstants.REP_GLOB, val("/hello")));
        lp1.setDenyRestrictions(newDenyRestrictions);
        Set<LocalRestriction> denyRestrictions2 = lp1.getDenyRestrictions();
        assertNotNull(denyRestrictions2);
        assertFalse(denyRestrictions2.isEmpty());
        assertEquals(newDenyRestrictions, denyRestrictions2);
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#setDenyRestrictions(java.util.Set)}.
     */
    @Test
    public void testSetDenyRestrictions() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertTrue(lp1.getDenyRestrictions().isEmpty());

        Set<LocalRestriction> newDenyRestrictions = new HashSet<>();
        newDenyRestrictions.add(new LocalRestriction(AccessControlConstants.REP_GLOB, val("/hello")));
        lp1.setDenyRestrictions(newDenyRestrictions);
        assertEquals(newDenyRestrictions, lp1.getDenyRestrictions());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#toString()}.
     */
    @Test
    public void testToString() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertNotNull(lp1.toString());
    }

    /**
     * Test method for {@link org.apache.sling.jcr.contentloader.LocalPrivilege#equals(java.lang.Object)}.
     */
    @Test
    public void testEqualsObject() {
        LocalPrivilege lp1 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertEquals(lp1, lp1);
        assertNotEquals(lp1, null);
        assertNotEquals(lp1, this);

        LocalPrivilege lp2 = new LocalPrivilege(PrivilegeConstants.JCR_WRITE);
        assertNotEquals(lp1, lp2);

        LocalPrivilege lp3 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertEquals(lp1, lp3);

        LocalPrivilege lp4 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp4.setAllow(true);
        assertNotEquals(lp1, lp4);

        LocalPrivilege lp5 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp5.setDeny(true);
        assertNotEquals(lp1, lp5);

        LocalPrivilege lp6 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp6.setAllowRestrictions(null);
        assertNotEquals(lp1, lp6);

        LocalPrivilege lp7 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp7.setDenyRestrictions(null);
        assertNotEquals(lp1, lp7);

        LocalPrivilege lp8 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp8.setAllowRestrictions(null);
        LocalPrivilege lp9 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertNotEquals(lp8, lp9);

        LocalPrivilege lp10 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp10.setDenyRestrictions(null);
        LocalPrivilege lp11 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertNotEquals(lp10, lp11);

        LocalPrivilege lp12 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp12.setAllowRestrictions(
                new HashSet<>(Arrays.asList(new LocalRestriction(AccessControlConstants.REP_GLOB, val("/hello")))));
        LocalPrivilege lp13 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertNotEquals(lp12, lp13);

        LocalPrivilege lp14 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp14.setDenyRestrictions(
                new HashSet<>(Arrays.asList(new LocalRestriction(AccessControlConstants.REP_GLOB, val("/hello")))));
        LocalPrivilege lp15 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertNotEquals(lp14, lp15);

        LocalPrivilege lp16 = new LocalPrivilege(null);
        LocalPrivilege lp17 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        assertNotEquals(lp16, lp17);

        LocalPrivilege lp18 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        LocalPrivilege lp19 = new LocalPrivilege(null);
        assertNotEquals(lp18, lp19);

        LocalPrivilege lp20 = new LocalPrivilege(null);
        LocalPrivilege lp21 = new LocalPrivilege(null);
        assertEquals(lp20, lp21);

        LocalPrivilege lp22 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp22.setAllowRestrictions(null);
        LocalPrivilege lp23 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp23.setAllowRestrictions(null);
        assertEquals(lp22, lp23);

        LocalPrivilege lp24 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp24.setDenyRestrictions(null);
        LocalPrivilege lp25 = new LocalPrivilege(PrivilegeConstants.JCR_READ);
        lp25.setDenyRestrictions(null);
        assertEquals(lp24, lp25);
    }
}
