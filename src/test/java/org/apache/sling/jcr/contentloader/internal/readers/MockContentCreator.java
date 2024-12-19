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
import javax.jcr.Value;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.apache.sling.jcr.contentloader.ContentCreator;

@SuppressWarnings("serial")
class MockContentCreator extends Stack<Map<String, Object>> implements ContentCreator {

    public static class FileDescription {
        public InputStream data;
        public String mimeType;
        public long lastModified;
        public String content;

        public FileDescription(InputStream data, String mimeType, long lastModified) throws IOException {
            this.data = data;
            this.mimeType = mimeType;
            this.lastModified = lastModified;
            BufferedReader reader = new BufferedReader(new InputStreamReader(data));
            this.content = reader.readLine();
            reader.close();
        }
    }

    public List<MockContentCreator.FileDescription> filesCreated = new ArrayList<MockContentCreator.FileDescription>();

    public MockContentCreator() {}

    public void createNode(String name, String primaryNodeType, String[] mixinNodeTypes) throws RepositoryException {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("primaryNodeType", primaryNodeType);
        map.put("mixinNodeTypes", mixinNodeTypes);
        this.push(map);
    }

    public void finishNode() throws RepositoryException {}

    protected void recordProperty(String name, Object value) {
        if (!isEmpty()) {
            Map<String, Object> map = peek();
            @SuppressWarnings("unchecked")
            Map<String, Object> propsMap =
                    (Map<String, Object>) map.computeIfAbsent("properties", key -> new HashMap<>());
            propsMap.put(name, value);
        }
    }

    public void createProperty(String name, int propertyType, String value) throws RepositoryException {
        recordProperty(name, value);
    }

    public void createProperty(String name, int propertyType, String[] values) throws RepositoryException {
        recordProperty(name, values);
    }

    public void createProperty(String name, Object value) throws RepositoryException {
        recordProperty(name, value);
    }

    public void createProperty(String name, Object[] values) throws RepositoryException {
        recordProperty(name, values);
    }

    public void createFileAndResourceNode(String name, InputStream data, String mimeType, long lastModified)
            throws RepositoryException {
        try {
            this.filesCreated.add(new FileDescription(data, mimeType, lastModified));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean switchCurrentNode(String subPath, String newNodeType) throws RepositoryException {
        return true;
    }

    public void createAce(String principal, String[] grantedPrivileges, String[] deniedPrivileges, String order)
            throws RepositoryException {}

    /*
     * (non-Javadoc)
     *
     * @see
     * org.apache.sling.jcr.contentloader.ContentCreator#createAce(java.lang.String,
     * java.lang.String[], java.lang.String[], java.lang.String, java.util.Map,
     * java.util.Map, java.util.Set)
     */
    @Override
    public void createAce(
            String principal,
            String[] grantedPrivileges,
            String[] deniedPrivileges,
            String order,
            Map<String, Value> restrictions,
            Map<String, Value[]> mvRestrictions,
            Set<String> removedRestrictionNames)
            throws RepositoryException {}

    public void createGroup(String name, String[] members, Map<String, Object> extraProperties)
            throws RepositoryException {}

    public void createUser(String name, String password, Map<String, Object> extraProperties)
            throws RepositoryException {}

    @Override
    public void finish() throws RepositoryException {}
}
