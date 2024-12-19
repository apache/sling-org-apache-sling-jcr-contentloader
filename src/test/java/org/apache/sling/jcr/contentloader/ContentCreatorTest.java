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

import java.io.InputStream;
import java.util.Map;

import org.junit.Test;

/**
 * Tests to verify the ContentCreator default methods
 * for an old impl that does not provide an implementation
 * for those methods
 */
public class ContentCreatorTest {

    private ContentCreator contentCreator = new ContentCreatorOldImpl();

    @Test(expected = UnsupportedOperationException.class)
    public void testCreateAce1() throws RepositoryException {
        contentCreator.createAce(null, null, null);
    }

    @Deprecated
    @Test(expected = UnsupportedOperationException.class)
    public void testCreateAce2() throws RepositoryException {
        contentCreator.createAce(null, null, null, null, null, null, null);
    }

    /**
     * An impl that doesn't provide implementations for the default methods
     */
    protected static class ContentCreatorOldImpl implements ContentCreator {

        @Override
        public void createNode(String name, String primaryNodeType, String[] mixinNodeTypes)
                throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finishNode() throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finish() throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createProperty(String name, int propertyType, String value) throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createProperty(String name, int propertyType, String[] values) throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createProperty(String name, Object value) throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createProperty(String name, Object[] values) throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createFileAndResourceNode(String name, InputStream data, String mimeType, long lastModified)
                throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean switchCurrentNode(String subPath, String newNodeType) throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createUser(String name, String password, Map<String, Object> extraProperties)
                throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createGroup(String name, String[] members, Map<String, Object> extraProperties)
                throws RepositoryException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createAce(String principal, String[] grantedPrivileges, String[] deniedPrivileges, String order)
                throws RepositoryException {
            throw new UnsupportedOperationException();
        }
    }
}
