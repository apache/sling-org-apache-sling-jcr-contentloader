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

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import javax.jcr.Binary;
import javax.jcr.Item;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.AccessControlList;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;
import javax.jcr.version.VersionManager;

import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.spi.security.privilege.PrivilegeConstants;
import org.apache.jackrabbit.util.ISO8601;
import org.apache.sling.jcr.base.util.AccessControlUtil;
import org.apache.sling.jcr.contentloader.ContentCreator;
import org.apache.sling.jcr.contentloader.ContentImportListener;
import org.apache.sling.jcr.contentloader.ContentReader;
import org.apache.sling.jcr.contentloader.ImportOptions;
import org.apache.sling.jcr.contentloader.LocalPrivilege;
import org.apache.sling.jcr.contentloader.LocalRestriction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>ContentLoader</code> creates the nodes and properties.
 *
 * @since 2.0.4
 */
public class DefaultContentCreator implements ContentCreator {

    private static final String JCR_LAST_MODIFIED = "jcr:lastModified";

    final Logger log = LoggerFactory.getLogger(DefaultContentCreator.class);

    private ImportOptions configuration;

    private final Deque<Node> parentNodeStack = new ArrayDeque<>();

    /**
     * The list of versionables.
     */
    private final List<Node> versionables = new ArrayList<>();

    /**
     * Delayed references during content loading for the reference property.
     */
    private final Map<String, List<String>> delayedReferences = new HashMap<>();

    private final Map<String, String[]> delayedMultipleReferences = new HashMap<>();

    private String defaultName;

    private Node createdRootNode;

    private boolean isParentNodeImport;

    private boolean ignoreOverwriteFlag = false;

    // default content type for createFile()
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * Helper class to get the mime type of a file.
     */
    private final ContentHelper contentHelper;

    /**
     * List of active content readers mapped by extension.
     */
    private Map<String, ContentReader> contentReaders;

    /**
     * Optional list of created nodes (for uninstall)
     */
    private List<String> createdNodes;

    /**
     * Optional listener to get notified about changes
     */
    private ContentImportListener importListener;

    private Set<String> appliedSet = new LinkedHashSet<>();
    private Set<String> importedNodes = new LinkedHashSet<>();

    /**
     * A one time use seed to randomize the user location.
     */
    private static final long INSTANCE_SEED = System.currentTimeMillis();

    /**
     * The number of levels folder used to store a user, could be a configuration
     * option.
     */
    private static final int STORAGE_LEVELS = 3;

    /**
     * Constructor.
     *
     * @param contentHelper
     *            Helper class to get the mime type of a file
     */
    public DefaultContentCreator(ContentHelper contentHelper) {
        this.contentHelper = contentHelper;
    }

    /**
     * Initialize this component.
     *
     * @param options
     *            The configuration for this import.
     * @param defaultContentReaders
     *            List of all content readers.
     * @param createdNodes
     *            Optional list to store new nodes (for uninstall)
     */
    public void init(final ImportOptions options, final Map<String, ContentReader> defaultContentReaders,
            final List<String> createdNodes, final ContentImportListener importListener) {
        this.configuration = options;
        // create list of allowed content readers
        this.contentReaders = new HashMap<>();
        defaultContentReaders.forEach((key,value)->{
            if (!configuration.isIgnoredImportProvider(key)) {
                contentReaders.put(key, value);
            }
        });
        this.createdNodes = createdNodes;
        this.importListener = importListener;
    }

    /**
     * If the defaultName is null, we are in PARENT_NODE import mode.
     *
     * @param parentNode
     * @param defaultName
     */
    public void prepareParsing(final Node parentNode, final String defaultName) {
        this.parentNodeStack.clear();
        this.parentNodeStack.push(parentNode);
        this.defaultName = defaultName;
        isParentNodeImport = defaultName == null;
        this.createdRootNode = null;
    }

    /**
     * Get the list of versionable nodes.
     */
    public List<Node> getVersionables() {
        return this.versionables;
    }

    /**
     * Clear the content loader.
     */
    public void clear() {
        this.versionables.clear();
    }

    /**
     * Set the ignore overwrite flag.
     *
     * @param flag
     */
    public void setIgnoreOverwriteFlag(boolean flag) {
        this.ignoreOverwriteFlag = flag;
    }

    /**
     * Get the created root node.
     */
    public Node getCreatedRootNode() {
        return this.createdRootNode;
    }

    /**
     * Get all active content readers.
     *
     * @return A map of readers
     */
    public Map<String, ContentReader> getContentReaders() {
        return this.contentReaders;
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentCreator#createNode(java.lang.String,
     *      java.lang.String, java.lang.String[])
     */
    public void createNode(String name, String primaryNodeType, String[] mixinNodeTypes) throws RepositoryException {
        final Node parentNode = this.parentNodeStack.peek();
        boolean isParentImport = (name == null && isParentNodeImport);
        if (name == null) {
            if (this.parentNodeStack.size() > 1) {
                throw new RepositoryException("Node needs to have a name.");
            }
            name = this.defaultName;
        }

        // if we are in parent node import mode, we don't create the root top level
        // node!
        if (!isParentImport || this.parentNodeStack.size() > 1) {
            // if node already exists but should be overwritten, delete it
            if (!this.ignoreOverwriteFlag && this.configuration.isOverwrite() && parentNode.hasNode(name)) {
                checkoutIfNecessary(parentNode);
                parentNode.getNode(name).remove();
            }

            // ensure repository node
            Node node;
            if (parentNode.hasNode(name)) {
                // use existing node
                node = parentNode.getNode(name);
            } else if (primaryNodeType == null) {
                // no explicit node type, use repository default
                checkoutIfNecessary(parentNode);
                node = parentNode.addNode(name);
                addNodeToCreatedList(node);
                if (this.importListener != null) {
                    this.importListener.onCreate(node.getPath());
                }
            } else {
                // explicit primary node type
                checkoutIfNecessary(parentNode);
                node = parentNode.addNode(name, primaryNodeType);
                addNodeToCreatedList(node);
                if (this.importListener != null) {
                    this.importListener.onCreate(node.getPath());
                }
            }

            // amend mixin node types
            if (mixinNodeTypes != null) {
                for (final String mixin : mixinNodeTypes) {
                    if (!node.isNodeType(mixin)) {
                        node.addMixin(mixin);
                    }
                }
            }
            
            importedNodes.add(node.getPath());
            
            // check if node is versionable
            final boolean addToVersionables = this.configuration.isCheckin() && node.isNodeType("mix:versionable");
            if (addToVersionables) {
                this.versionables.add(node);
            }

            this.parentNodeStack.push(node);
            if (this.createdRootNode == null) {
                this.createdRootNode = node;
            }
        }
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentCreator#createProperty(java.lang.String,
     *      int, java.lang.String)
     */
    public void createProperty(String name, int propertyType, String value) throws RepositoryException {
        appliedSet.add(name);
        final Node node = this.parentNodeStack.peek();
        // check if the property already exists and isPropertyOverwrite() is false,
        // don't overwrite it in this case
        if (node.hasProperty(name) && !this.configuration.isPropertyOverwrite() && !node.getProperty(name).isNew()) {
            return;
        }

        if (propertyType == PropertyType.REFERENCE) {
            // need to resolve the reference
            String propPath = node.getPath() + "/" + name;
            String uuid = getUUID(node.getSession(), propPath, getAbsPath(node, value));
            if (uuid != null) {
                checkoutIfNecessary(node);
                node.setProperty(name, uuid, propertyType);
                if (this.importListener != null) {
                    this.importListener.onCreate(node.getProperty(name).getPath());
                }
            }
        } else if ("jcr:isCheckedOut".equals(name)) {
            // don't try to write the property but record its state
            // for later checkin if set to false
            final boolean checkedout = Boolean.parseBoolean(value);
            if (!checkedout && !this.versionables.contains(node)) {
                this.versionables.add(node);
            }
        } else if (propertyType == PropertyType.DATE) {
            checkoutIfNecessary(node);
            node.setProperty(name, ISO8601.parse(value));
            if (this.importListener != null) {
                this.importListener.onCreate(node.getProperty(name).getPath());
            }
        } else {
            checkoutIfNecessary(node);
            if (propertyType == PropertyType.UNDEFINED) {
                node.setProperty(name, value);
            } else {
                node.setProperty(name, value, propertyType);
            }
            if (this.importListener != null) {
                this.importListener.onCreate(node.getProperty(name).getPath());
            }
        }
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentCreator#createProperty(java.lang.String,
     *      int, java.lang.String[])
     */
    public void createProperty(String name, int propertyType, String[] values) throws RepositoryException {
        appliedSet.add(name);
        final Node node = this.parentNodeStack.peek();
        // check if the property already exists and isPropertyOverwrite() is false,
        // don't overwrite it in this case
        if (node.hasProperty(name) && !this.configuration.isPropertyOverwrite() && !node.getProperty(name).isNew()) {
            return;
        }
        if (propertyType == PropertyType.REFERENCE) {
            String propPath = node.getPath() + "/" + name;
            boolean hasAll = true;
            String[] uuids = new String[values.length];
            String[] uuidOrPaths = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                uuids[i] = getUUID(node.getSession(), propPath, getAbsPath(node, values[i]));
                uuidOrPaths[i] = uuids[i] != null ? uuids[i] : getAbsPath(node, values[i]);
                if (uuids[i] == null)
                    hasAll = false;
            }
            checkoutIfNecessary(node);
            node.setProperty(name, uuids, propertyType);
            if (this.importListener != null) {
                this.importListener.onCreate(node.getProperty(name).getPath());
            }
            if (!hasAll) {
                delayedMultipleReferences.put(propPath, uuidOrPaths);
            }
        } else if (propertyType == PropertyType.DATE) {
            checkoutIfNecessary(node);

            // This modification is to remove the colon in the JSON Timezone
            ValueFactory valueFactory = node.getSession().getValueFactory();
            Value[] jcrValues = new Value[values.length];

            for (int i = 0; i < values.length; i++) {
                jcrValues[i] = valueFactory.createValue(ISO8601.parse(values[i]));
            }

            node.setProperty(name, jcrValues, propertyType);

            if (this.importListener != null) {
                this.importListener.onCreate(node.getProperty(name).getPath());
            }
        } else {
            checkoutIfNecessary(node);
            if (propertyType == PropertyType.UNDEFINED) {
                node.setProperty(name, values);
            } else {
                node.setProperty(name, values, propertyType);
            }
            if (this.importListener != null) {
                this.importListener.onCreate(node.getProperty(name).getPath());
            }
        }
    }

    protected Value createValue(final ValueFactory factory, Object value) throws RepositoryException {
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return factory.createValue((Long) value);
        } else if (value instanceof Date) {
            final Calendar c = Calendar.getInstance();
            c.setTime((Date) value);
            return factory.createValue(c);
        } else if (value instanceof Calendar) {
            return factory.createValue((Calendar) value);
        } else if (value instanceof Double) {
            return factory.createValue((Double) value);
        } else if (value instanceof Boolean) {
            return factory.createValue((Boolean) value);
        } else if (value instanceof InputStream) {
            Binary binary = factory.createBinary((InputStream) value);
            return factory.createValue(binary);
        } else {
            return factory.createValue(value.toString());
        }
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentCreator#createProperty(java.lang.String,
     *      java.lang.Object)
     */
    public void createProperty(String name, Object value) throws RepositoryException {
        createProperty(name, value, false);
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentCreator#createProperty(java.lang.String,
     *      java.lang.Object[])
     */
    public void createProperty(String name, Object[] values) throws RepositoryException {
        createProperty(name, values, false);
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentCreator#finishNode()
     */
    public void finishNode() throws RepositoryException {
        final Node node = this.parentNodeStack.pop();
        cleanUpNode(node);
        // resolve REFERENCE property values pointing to this node
        resolveReferences(node);
    }

    private void cleanUpNode(Node node) throws RepositoryException {
        if (configuration.isPropertyMerge()) {
            PropertyIterator it = node.getProperties();
            while (it.hasNext()) {
                Property prop= it.nextProperty();
                String propertyName = prop.getName();
                if (appliedSet.contains(propertyName)) {
                    continue;
                }
                if (!prop.getDefinition().isProtected()) {
                    if (importListener != null) {
                        importListener.onDelete(prop.getPath());
                    }
                    prop.remove();
                    log.trace(propertyName);
                }
            }
        }
    }

    private void addNodeToCreatedList(Node node) throws RepositoryException {
        if (this.createdNodes != null) {
            this.createdNodes.add(node.getSession().getWorkspace().getName() + ":" + node.getPath());
        }
    }

    private String getAbsPath(Node node, String path) throws RepositoryException {
        if (path.startsWith("/")) {
            return path;
        }

        while (path.startsWith("../")) {
            path = path.substring(3);
            node = node.getParent();
        }

        while (path.startsWith("./")) {
            path = path.substring(2);
        }

        return node.getPath() + "/" + path;
    }

    private String getUUID(Session session, String propPath, String referencePath) throws RepositoryException {
        if (session.itemExists(referencePath)) {
            Item item = session.getItem(referencePath);
            if (item.isNode()) {
                Node refNode = (Node) item;
                if (refNode.isNodeType("mix:referenceable")) {
                    return refNode.getIdentifier();
                }
            }
        } else {
            // not existing yet, keep for delayed setting
            List<String> current = delayedReferences.computeIfAbsent(referencePath, k -> new ArrayList<>());
            current.add(propPath);
        }
        // no UUID found
        return null;
    }

    private void resolveReferences(Node node) throws RepositoryException {
        List<String> props = delayedReferences.remove(node.getPath());
        if (props == null || props.isEmpty()) {
            return;
        }

        // check whether we can set at all
        if (!node.isNodeType("mix:referenceable")) {
            return;
        }

        Session session = node.getSession();
        String uuid = node.getIdentifier();

        for (String property : props) {
            String name = getName(property);
            Node parentNode = getParentNode(session, property);
            if (parentNode != null) {
                checkoutIfNecessary(parentNode);
                if (parentNode.hasProperty(name) && parentNode.getProperty(name).getDefinition().isMultiple()) {
                    boolean hasAll = true;
                    String[] uuidOrPaths = delayedMultipleReferences.get(property);
                    String[] uuids = new String[uuidOrPaths.length];
                    for (int i = 0; i < uuidOrPaths.length; i++) {
                        // is the reference still a path
                        if (uuidOrPaths[i].startsWith("/")) {
                            if (uuidOrPaths[i].equals(node.getPath())) {
                                uuidOrPaths[i] = uuid;
                                uuids[i] = uuid;
                            } else {
                                uuids[i] = null;
                                hasAll = false;
                            }
                        } else {
                            uuids[i] = uuidOrPaths[i];
                        }
                    }
                    parentNode.setProperty(name, uuids, PropertyType.REFERENCE);
                    if (this.importListener != null) {
                        this.importListener.onCreate(parentNode.getProperty(name).getPath());
                    }
                    if (hasAll) {
                        delayedMultipleReferences.remove(property);
                    }
                } else {
                    parentNode.setProperty(name, uuid, PropertyType.REFERENCE);
                    if (this.importListener != null) {
                        this.importListener.onCreate(parentNode.getProperty(name).getPath());
                    }
                }
            }
        }
    }

    /**
     * Gets the name part of the <code>path</code>. The name is the part of the path
     * after the last slash (or the complete path if no slash is contained).
     *
     * @param path
     *            The path from which to extract the name part.
     * @return The name part.
     */
    private String getName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }

    private Node getParentNode(Session session, String path) throws RepositoryException {
        int lastSlash = path.lastIndexOf('/');

        // not an absolute path, cannot find parent
        if (lastSlash < 0) {
            return null;
        }

        // node below root
        if (lastSlash == 0) {
            return session.getRootNode();
        }

        // item in the hierarchy
        path = path.substring(0, lastSlash);
        if (!session.itemExists(path)) {
            return null;
        }

        Item item = session.getItem(path);
        return (item.isNode()) ? (Node) item : null;
    }

    private void createProperty(String name, Object value, boolean overwriteExisting) throws RepositoryException {
        final Node node = this.parentNodeStack.peek();
        // check if the property already exists, don't overwrite it in this case
        if (node.hasProperty(name) && !node.getProperty(name).isNew() && !overwriteExisting) {
            return;
        }
        if (value == null) {
            if (node.hasProperty(name)) {
                checkoutIfNecessary(node);
                String propPath = node.getProperty(name).getPath();
                node.getProperty(name).remove();
                if (this.importListener != null) {
                    this.importListener.onDelete(propPath);
                }
            }
        } else {
            checkoutIfNecessary(node);
            final Value jcrValue = this.createValue(node.getSession().getValueFactory(), value);
            node.setProperty(name, jcrValue);
            if (this.importListener != null) {
                this.importListener.onModify(node.getProperty(name).getPath());
            }
        }
        appliedSet.add(name);
    }

    private void createProperty(String name, Object[] values, boolean overwriteExisting) throws RepositoryException {
        final Node node = this.parentNodeStack.peek();
        // check if the property already exists, don't overwrite it in this case
        if (node.hasProperty(name) && !node.getProperty(name).isNew() && !overwriteExisting) {
            return;
        }
        if (values == null || values.length == 0) {
            if (node.hasProperty(name)) {
                checkoutIfNecessary(node);
                String propPath = node.getProperty(name).getPath();
                node.getProperty(name).remove();
                if (this.importListener != null) {
                    this.importListener.onDelete(propPath);
                }
            }
        } else {
            checkoutIfNecessary(node);
            final Value[] jcrValues = new Value[values.length];
            for (int i = 0; i < values.length; i++) {
                jcrValues[i] = this.createValue(node.getSession().getValueFactory(), values[i]);
            }
            node.setProperty(name, jcrValues);
            if (this.importListener != null) {
                this.importListener.onModify(node.getProperty(name).getPath());
            }
        }
        appliedSet.add(name);
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentCreator#createFileAndResourceNode(java.lang.String,
     *      java.io.InputStream, java.lang.String, long)
     */
    public void createFileAndResourceNode(String name, InputStream data, String mimeType, long lastModified)
            throws RepositoryException {
        int lastSlash = name.lastIndexOf('/');
        name = (lastSlash < 0) ? name : name.substring(lastSlash + 1);
        final Node parentNode = this.parentNodeStack.peek();

        // if node already exists but should be overwritten, delete it
        if (parentNode.hasNode(name)) {
            this.parentNodeStack.push(parentNode.getNode(name));
            Node contentNode = parentNode.getNode(name).getNode("jcr:content");
            this.parentNodeStack.push(contentNode);
            long nodeLastModified = 0L;
            if (contentNode.hasProperty(JCR_LAST_MODIFIED)) {
                nodeLastModified = contentNode.getProperty(JCR_LAST_MODIFIED).getDate().getTimeInMillis();
            }
            if (!this.configuration.isOverwrite() && nodeLastModified >= lastModified) {
                return;
            }
            log.debug("Updating {} lastModified:{} New Content LastModified:{}", parentNode.getNode(name).getPath(),
                    new Date(nodeLastModified), new Date(lastModified));
        } else {
            this.createNode(name, "nt:file", null);
            this.createNode("jcr:content", "nt:resource", null);
        }

        // ensure content type
        if (mimeType == null) {
            mimeType = contentHelper.getMimeType(name);
            if (mimeType == null) {
                log.debug("createFile: Cannot find content type for {}, using {}", name, DEFAULT_CONTENT_TYPE);
                mimeType = DEFAULT_CONTENT_TYPE;
            }
        }

        // ensure sensible last modification date
        if (lastModified <= 0) {
            lastModified = System.currentTimeMillis();
        }
        this.createProperty("jcr:mimeType", mimeType, true);
        this.createProperty(JCR_LAST_MODIFIED, lastModified, true);
        this.createProperty("jcr:data", data, true);
    }

    /**
     * @see org.apache.sling.jcr.contentloader.ContentCreator#switchCurrentNode(java.lang.String,
     *      java.lang.String)
     */
    public boolean switchCurrentNode(String subPath, String newNodeType) throws RepositoryException {
        if (subPath.startsWith("/")) {
            subPath = subPath.substring(1);
        }
        final StringTokenizer st = new StringTokenizer(subPath, "/");
        Node node = this.parentNodeStack.peek();
        while (st.hasMoreTokens()) {
            final String token = st.nextToken();
            if (!node.hasNode(token)) {
                if (newNodeType == null) {
                    return false;
                }
                checkoutIfNecessary(node);
                final Node n = node.addNode(token, newNodeType);
                addNodeToCreatedList(n);
                if (this.importListener != null) {
                    this.importListener.onCreate(node.getPath());
                }
            }
            node = node.getNode(token);
        }
        this.parentNodeStack.push(node);
        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.sling.jcr.contentloader.ContentCreator#createGroup(java.lang.
     * String, java.lang.String[], java.util.Map)
     */
    public void createGroup(final String name, String[] members, Map<String, Object> extraProperties)
            throws RepositoryException {

        final Node parentNode = this.parentNodeStack.peek();
        Session session = parentNode.getSession();

        UserManager userManager = AccessControlUtil.getUserManager(session);
        Authorizable authorizable = userManager.getAuthorizable(name);
        if (authorizable == null) {
            // principal does not exist yet, so create it
            Group group = userManager.createGroup(() -> name, hashPath(name));
            authorizable = group;
        } else {
            // principal already exists, check to make sure it is the expected type
            if (!authorizable.isGroup()) {
                throw new RepositoryException("A user already exists with the requested name: " + name);
            }
            // group already exists so just update it below
        }
        // update the group members
        if (members != null) {
            Group group = (Group) authorizable;
            for (String member : members) {
                Authorizable memberAuthorizable = userManager.getAuthorizable(member);
                if (memberAuthorizable != null) {
                    group.addMember(memberAuthorizable);
                }
            }
        }
        if (extraProperties != null) {
            ValueFactory valueFactory = session.getValueFactory();
            Set<Entry<String, Object>> entrySet = extraProperties.entrySet();
            for (Entry<String, Object> entry : entrySet) {
                Value value = createValue(valueFactory, entry.getValue());
                authorizable.setProperty(entry.getKey(), value);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.sling.jcr.contentloader.ContentCreator#createUser(java.lang.
     * String, java.lang.String, java.util.Map)
     */
    public void createUser(final String name, String password, Map<String, Object> extraProperties)
            throws RepositoryException {
        final Node parentNode = this.parentNodeStack.peek();
        Session session = parentNode.getSession();

        UserManager userManager = AccessControlUtil.getUserManager(session);
        Authorizable authorizable = userManager.getAuthorizable(name);
        if (authorizable == null) {
            // principal does not exist yet, so create it
            User user = userManager.createUser(name, password, () -> name, hashPath(name));
            authorizable = user;
        } else {
            // principal already exists, check to make sure it is the expected type
            if (authorizable.isGroup()) {
                throw new RepositoryException("A group already exists with the requested name: " + name);
            }
            // user already exists so just update it below
        }
        if (extraProperties != null) {
            ValueFactory valueFactory = session.getValueFactory();
            Set<Entry<String, Object>> entrySet = extraProperties.entrySet();
            for (Entry<String, Object> entry : entrySet) {
                Value value = createValue(valueFactory, entry.getValue());
                authorizable.setProperty(entry.getKey(), value);
            }
        }
    }

    /**
     * @param item
     * @return a parent path fragment for the item.
     */
    protected String hashPath(String item) throws RepositoryException {
        try {
            final String hash = digest("sha1", (INSTANCE_SEED + item).getBytes("UTF-8"));
            return IntStream.range(0, STORAGE_LEVELS)
                    .mapToObj(i -> hash.substring(i * 2, (i * 2) + 2))
                    .collect(Collectors.joining("/", "", "/"));
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new RepositoryException("Unable to hash the path.", e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.apache.sling.jcr.contentloader.ContentCreator#createAce(java.lang.String,
     * java.lang.String, java.lang.String[], java.lang.String[])
     */
    public void createAce(String principalId, String[] grantedPrivilegeNames, String[] deniedPrivilegeNames,
            String order) throws RepositoryException {
        Map<String, LocalPrivilege> privilegeToLocalPrivilegesMap = toLocalPrivileges(grantedPrivilegeNames,
                deniedPrivilegeNames);

        createAce(principalId, new ArrayList<>(privilegeToLocalPrivilegesMap.values()), order);
    }

    /**
     * Convert the privilege names to LocalPrivileges
     * 
     * @param grantedPrivilegeNames the granted privileges
     * @param deniedPrivilegeNames the denied privileges
     * @return map of privilege names to LocalPrivilege data
     */
    protected Map<String, LocalPrivilege> toLocalPrivileges(String[] grantedPrivilegeNames,
            String[] deniedPrivilegeNames) {
        // first start with an empty map
        Map<String, LocalPrivilege> privilegeToLocalPrivilegesMap = new LinkedHashMap<>();

        if (grantedPrivilegeNames != null) {
            for (String pn: grantedPrivilegeNames) {
                LocalPrivilege lp = privilegeToLocalPrivilegesMap.computeIfAbsent(pn, LocalPrivilege::new);
                lp.setAllow(true);
            }
        }

        if (deniedPrivilegeNames != null) {
            for (String pn: deniedPrivilegeNames) {
                LocalPrivilege lp = privilegeToLocalPrivilegesMap.computeIfAbsent(pn, LocalPrivilege::new);
                lp.setDeny(true);
            }
        }
        return privilegeToLocalPrivilegesMap;
    }

    /**
     * @deprecated use {@link #createAce(String, Collection, String)} instead
     */
    @Deprecated
    @Override
    public void createAce(String principalId, String[] grantedPrivilegeNames, String[] deniedPrivilegeNames,
            String order, Map<String, Value> restrictions, Map<String, Value[]> mvRestrictions,
            Set<String> removedRestrictionNames) throws RepositoryException {
        Map<String, LocalPrivilege> privilegeToLocalPrivilegesMap = toLocalPrivileges(grantedPrivilegeNames,
                deniedPrivilegeNames);

        Set<LocalRestriction> restrictionsSet = new HashSet<>();
        if (restrictions != null) {
            for (Entry<String, Value> entry: restrictions.entrySet()) {
                LocalRestriction lr = new LocalRestriction(entry.getKey(), entry.getValue());
                restrictionsSet.add(lr);
            }
        }
        if (mvRestrictions != null) {
            for (Entry<String, Value[]> entry: mvRestrictions.entrySet()) {
                LocalRestriction lr = new LocalRestriction(entry.getKey(), entry.getValue());
                restrictionsSet.add(lr);
            }
        }

        if (!restrictionsSet.isEmpty()) {
            for (LocalPrivilege entry: privilegeToLocalPrivilegesMap.values()) {
                if (entry.isAllow()) {
                    entry.setAllowRestrictions(restrictionsSet);
                }
                if (entry.isDeny()) {
                    entry.setDenyRestrictions(restrictionsSet);
                }
            }
        }

        createAce(principalId, new ArrayList<>(privilegeToLocalPrivilegesMap.values()), order);
    }

    @Override
    public void createAce(String principalId, Collection<LocalPrivilege> privileges, String order)
            throws RepositoryException {
        final Node parentNode = this.parentNodeStack.peek();
        Session jcrSession = parentNode.getSession();

        // validate that the principal name is valid
        PrincipalManager principalManager = AccessControlUtil.getPrincipalManager(jcrSession);
        Principal principal = principalId == null ? null : principalManager.getPrincipal(principalId);
        if (principal == null) {
            // SLING-7268 - as pointed out in OAK-5496, we cannot successfully use
            // PrincipalManager#getPrincipal in oak
            // without the session that created the principal getting saved first (and a
            // subsequent index update).
            // Workaround by trying the UserManager#getAuthorizable API to locate the
            // principal.
            UserManager userManager = AccessControlUtil.getUserManager(jcrSession);
            final Authorizable authorizable = userManager.getAuthorizable(principalId);
            if (authorizable != null) {
                principal = authorizable.getPrincipal();
            }
        }

        if (principal == null) {
            throw new RepositoryException("No principal found for id: " + principalId);
        }

        // validate that the privilege names are valid
        AccessControlManager acm = AccessControlUtil.getAccessControlManager(jcrSession);
        for (LocalPrivilege localPrivilege: privileges) {
            localPrivilege.checkPrivilege(acm);
        }

        String resourcePath = parentNode.getPath();

        // build a list of each of the LocalPrivileges that have the same restrictions
        Map<Set<LocalRestriction>, List<LocalPrivilege>> allowRestrictionsToLocalPrivilegesMap = new HashMap<>();
        Map<Set<LocalRestriction>, List<LocalPrivilege>> denyRestrictionsToLocalPrivilegesMap = new HashMap<>();
        for (LocalPrivilege localPrivilege: privileges) {
            if (localPrivilege.isAllow()) {
                List<LocalPrivilege> list = allowRestrictionsToLocalPrivilegesMap.computeIfAbsent(localPrivilege.getAllowRestrictions(), key -> new ArrayList<>());
                list.add(localPrivilege);
            }
            if (localPrivilege.isDeny()) {
                List<LocalPrivilege> list = denyRestrictionsToLocalPrivilegesMap.computeIfAbsent(localPrivilege.getDenyRestrictions(), key -> new ArrayList<>());
                list.add(localPrivilege);
            }
        }

        try {
            // Get or create the ACL for the node.
            JackrabbitAccessControlList acl = getAcl(acm, resourcePath, principal);

            // remove all the old aces for the principal
            order = removeAces(resourcePath, order, principal, acl);

            // now add all the new aces that we have collected
            Map<Privilege, Integer> privilegeLongestDepthMap = buildPrivilegeLongestDepthMap(acm.privilegeFromName(PrivilegeConstants.JCR_ALL));
            addAces(resourcePath, principal, denyRestrictionsToLocalPrivilegesMap, false, acl, privilegeLongestDepthMap);
            addAces(resourcePath, principal, allowRestrictionsToLocalPrivilegesMap, true, acl, privilegeLongestDepthMap);

            // reorder the aces
            reorderAccessControlEntries(acl, principal, order);

            // Store the actual changes.
            acm.setPolicy(acl.getPath(), acl);
        } catch (RepositoryException re) {
            throw new RepositoryException("Failed to create ace.", re);
        }
    }

    /**
     * If the privilege is contained in multiple aggregate privileges, then
     * calculate the instance with the greatest depth.
     */
    private static void toLongestDepth(int parentDepth, Privilege parentPrivilege, Map<Privilege, Integer> privilegeToLongestDepth) {
        Privilege[] declaredAggregatePrivileges = parentPrivilege.getDeclaredAggregatePrivileges();
        for (Privilege privilege : declaredAggregatePrivileges) {
            Integer oldValue = privilegeToLongestDepth.get(privilege);
            int candidateDepth = parentDepth + 1;
            if (oldValue == null || oldValue.intValue() < candidateDepth) {
                privilegeToLongestDepth.put(privilege, candidateDepth);

                // continue drilling down to the leaf privileges
                toLongestDepth(candidateDepth, privilege, privilegeToLongestDepth);
            }
        }
    }

    /**
     * Calculate the longest path for each of the possible privileges
     * 
     * @param jcrSession the current users JCR session
     * @return map where the key is the privilege and the value is the longest path
     */
    public static Map<Privilege, Integer> buildPrivilegeLongestDepthMap(Privilege jcrAll) {
        Map<Privilege, Integer> privilegeToLongestPath = new HashMap<>();
        privilegeToLongestPath.put(jcrAll, 1);
        toLongestDepth(1, jcrAll, privilegeToLongestPath);
        return privilegeToLongestPath;
    }

    /**
     * Lookup the ACL for the given resource
     * 
     * @param acm the access control manager
     * @param resourcePath the resource path
     * @param principal the principal for principalbased ACL
     * @return the found ACL object
     */
    protected JackrabbitAccessControlList getAcl(@NotNull AccessControlManager acm, String resourcePath, Principal principal)
            throws RepositoryException {
        AccessControlPolicy[] policies = acm.getPolicies(resourcePath);
        JackrabbitAccessControlList acl = null;
        for (AccessControlPolicy policy : policies) {
            if (policy instanceof JackrabbitAccessControlList) {
                acl = (JackrabbitAccessControlList) policy;
                break;
            }
        }
        if (acl == null) {
            AccessControlPolicyIterator applicablePolicies = acm.getApplicablePolicies(resourcePath);
            while (applicablePolicies.hasNext()) {
                AccessControlPolicy policy = applicablePolicies.nextAccessControlPolicy();
                if (policy instanceof JackrabbitAccessControlList) {
                    acl = (JackrabbitAccessControlList) policy;
                    break;
                }
            }
        }
        return acl;
    }

    /**
     * Remove all of the ACEs for the specified principal from the ACL
     * 
     * @param order the requested order (may be null)
     * @param principal the principal whose aces should be removed
     * @param acl the access control list to update
     * @return the original order if it was supplied, otherwise the order of the first ACE 
     */
    protected String removeAces(@NotNull String resourcePath, @Nullable String order, @NotNull Principal principal, @NotNull JackrabbitAccessControlList acl) // NOSONAR
            throws RepositoryException {
        AccessControlEntry[] existingAccessControlEntries = acl.getAccessControlEntries();

        if (order == null || order.length() == 0) {
            //order not specified, so keep track of the original ACE position.
            Set<Principal> processedPrincipals = new HashSet<>();
            for (int j = 0; j < existingAccessControlEntries.length; j++) {
                AccessControlEntry ace = existingAccessControlEntries[j];
                Principal principal2 = ace.getPrincipal();
                if (principal2.equals(principal)) {
                    order = String.valueOf(processedPrincipals.size());
                    break;
                } else {
                    processedPrincipals.add(principal2);
                }
            }
        }

        for (int j = 0; j < existingAccessControlEntries.length; j++) {
            AccessControlEntry ace = existingAccessControlEntries[j];
            if (ace.getPrincipal().equals(principal)) {
                acl.removeAccessControlEntry(ace);
            }
        }
        return order;
    }

    /**
     * Add ACEs for the specified principal to the ACL.  One ACE is added for each unique
     * restriction set.
     * 
     * @param resourcePath the path of the resource
     * @param principal the principal whose aces should be added
     * @param restrictionsToLocalPrivilegesMap the map containing the restrictions mapped to the LocalPrivlege items with those resrictions
     * @param isAllow true for 'allow' ACE, false for 'deny' ACE
     * @param acl the access control list to update
     */
    protected void addAces(@NotNull String resourcePath, @NotNull Principal principal,
            @NotNull Map<Set<LocalRestriction>, List<LocalPrivilege>> restrictionsToLocalPrivilegesMap,
            boolean isAllow,
            @NotNull JackrabbitAccessControlList acl,
            Map<Privilege, Integer> privilegeLongestDepthMap) throws RepositoryException {

        List<Entry<Set<LocalRestriction>, List<LocalPrivilege>>> sortedEntries = new ArrayList<>(restrictionsToLocalPrivilegesMap.entrySet());
        // sort the entries by the most shallow depth of the contained privileges
        Collections.sort(sortedEntries, (e1, e2) -> {
                        int shallowestDepth1 = Integer.MAX_VALUE;
                        for (LocalPrivilege lp : e1.getValue()) {
                            Integer depth = privilegeLongestDepthMap.get(lp.getPrivilege());
                            if (depth != null && depth.intValue() < shallowestDepth1) {
                                shallowestDepth1 = depth.intValue();
                            }
                        }
                        int shallowestDepth2 = Integer.MAX_VALUE;
                        for (LocalPrivilege lp : e2.getValue()) {
                            Integer depth = privilegeLongestDepthMap.get(lp.getPrivilege());
                            if (depth != null && depth.intValue() < shallowestDepth2) {
                                shallowestDepth2 = depth.intValue();
                            }
                        }
                        return Integer.compare(shallowestDepth1, shallowestDepth2);
                    });

        for (Entry<Set<LocalRestriction>, List<LocalPrivilege>> entry: sortedEntries) {
            Set<Privilege> privilegesSet = new HashSet<>();
            Map<String, Value> restrictions = new HashMap<>(); 
            Map<String, Value[]> mvRestrictions = new HashMap<>();

            Set<LocalRestriction> localRestrictions = entry.getKey();
            for (LocalRestriction localRestriction : localRestrictions) {
                if (localRestriction.isMultiValue()) {
                    mvRestrictions.put(localRestriction.getName(), localRestriction.getValues());
                } else {
                    restrictions.put(localRestriction.getName(), localRestriction.getValue());
                }
            }

            for (LocalPrivilege localPrivilege : entry.getValue()) {
                privilegesSet.add(localPrivilege.getPrivilege());
            }

            if (!privilegesSet.isEmpty()) {
                acl.addEntry(principal, privilegesSet.toArray(new Privilege[privilegesSet.size()]), isAllow, restrictions, mvRestrictions);
            }
        }
    }

    /**
     * Move the ACE(s) for the specified principal to the position specified by the 'order'
     * parameter. This is a copy of the private AccessControlUtil.reorderAccessControlEntries method.
     *
     * @param acl the acl of the node containing the ACE to position
     * @param principal the user or group of the ACE to position
     * @param order where the access control entry should go in the list.
     *         Value should be one of these:
     *         <table>
     *          <caption>Values</caption>
     *          <tr><td>first</td><td>Place the target ACE as the first amongst its siblings</td></tr>
     *          <tr><td>last</td><td>Place the target ACE as the last amongst its siblings</td></tr>
     *          <tr><td>before xyz</td><td>Place the target ACE immediately before the sibling whose name is xyz</td></tr>
     *          <tr><td>after xyz</td><td>Place the target ACE immediately after the sibling whose name is xyz</td></tr>
     *          <tr><td>numeric</td><td>Place the target ACE at the specified index</td></tr>
     *         </table>
     * @throws RepositoryException
     * @throws UnsupportedRepositoryOperationException
     * @throws AccessControlException
     */
    private static void reorderAccessControlEntries(AccessControlList acl,
            Principal principal, String order) throws RepositoryException {
        if (order == null || order.length() == 0) {
            return; //nothing to do
        }
        if (acl instanceof JackrabbitAccessControlList) {
            JackrabbitAccessControlList jacl = (JackrabbitAccessControlList)acl;

            AccessControlEntry[] accessControlEntries = jacl.getAccessControlEntries();
            if (accessControlEntries.length <= 1) {
                return; //only one ACE, so nothing to reorder.
            }

            AccessControlEntry beforeEntry = null;
            if ("first".equals(order)) {
                beforeEntry = accessControlEntries[0];
            } else if ("last".equals(order)) {
                // add to the end is the same as default
            } else if (order.startsWith("before ")) {
                String beforePrincipalName = order.substring(7);

                //find the index of the ACE of the 'before' principal
                for (int i=0; i < accessControlEntries.length; i++) {
                    if (beforePrincipalName.equals(accessControlEntries[i].getPrincipal().getName())) {
                        //found it!
                        beforeEntry = accessControlEntries[i];
                        break;
                    }
                }

                if (beforeEntry == null) {
                    //didn't find an ACE that matched the 'before' principal
                    throw new IllegalArgumentException("No ACE was found for the specified principal: " + beforePrincipalName);
                }
            } else if (order.startsWith("after ")) {
                String afterPrincipalName = order.substring(6);

                boolean foundPrincipal = false;
                //find the index of the ACE of the 'after' principal
                for (int i = accessControlEntries.length - 1; i >= 0; i--) {
                    if (afterPrincipalName.equals(accessControlEntries[i].getPrincipal().getName())) {
                        //found it!
                        foundPrincipal = true;

                        // the 'before' ACE is the next one after the 'after' ACE
                        if (i >= accessControlEntries.length - 1) {
                            //the after is the last one in the list
                            beforeEntry = null;
                        } else {
                            beforeEntry = accessControlEntries[i + 1];
                        }
                        break;
                    }
                }

                if (!foundPrincipal) {
                    //didn't find an ACE that matched the 'after' principal
                    throw new IllegalArgumentException("No ACE was found for the specified principal: " + afterPrincipalName);
                }
            } else {
                int index = -1;
                try {
                    index = Integer.parseInt(order);
                } catch (NumberFormatException nfe) {
                    //not a number.
                    throw new IllegalArgumentException("Illegal value for the order parameter: " + order);
                }
                if (index > accessControlEntries.length) {
                    //invalid index
                    throw new IndexOutOfBoundsException("Index value is too large: " + index);
                }

                //the index value is the index of the principal.  A principal may have more
                // than one ACEs (deny + grant), so we need to compensate.
                Map<Principal, Integer> principalToIndex = new HashMap<>();
                for (int i = 0; i < accessControlEntries.length; i++) {
                    Principal principal2 = accessControlEntries[i].getPrincipal();
                    Integer idx = i;
                    principalToIndex.computeIfAbsent(principal2, key -> idx);
                }
                Integer[] sortedIndexes = principalToIndex.values().stream()
                        .sorted()
                        .toArray(size -> new Integer[size]);
                if (index >= 0 && index < sortedIndexes.length - 1) {
                    int idx = sortedIndexes[index];
                    beforeEntry = accessControlEntries[idx];
                }
            }

            if (beforeEntry != null) {
                //now loop through the entries to move the affected ACEs to the specified
                // position.
                for (AccessControlEntry ace : accessControlEntries) {
                    if (principal.equals(ace.getPrincipal())) {
                        //this ACE is for the specified principal.
                        jacl.orderBefore(ace, beforeEntry);
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("The acl must be an instance of JackrabbitAccessControlList");
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.sling.jcr.contentloader.ContentCreator#getParent()
     */
    @Override
    public Node getParent() {
        return this.parentNodeStack.peek();
    }

    /**
     * used for the md5
     */
    private static final char[] hexTable = "0123456789abcdef".toCharArray();

    /**
     * Digest the plain string using the given algorithm.
     *
     * @param algorithm
     *            The alogrithm for the digest. This algorithm must be supported by
     *            the MessageDigest class.
     * @param data
     *            the data to digest with the given algorithm
     * @return The digested plain text String represented as Hex digits.
     * @throws java.security.NoSuchAlgorithmException
     *             if the desired algorithm is not supported by the MessageDigest
     *             class.
     */
    public static String digest(String algorithm, byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] digest = md.digest(data);
        StringBuilder res = new StringBuilder(digest.length * 2);
        for (int i = 0; i < digest.length; i++) {
            byte b = digest[i];
            res.append(hexTable[(b >> 4) & 15]);
            res.append(hexTable[b & 15]);
        }
        return res.toString();
    }

    /**
     * Find an ancestor that is versionable
     */
    protected Node findVersionableAncestor(Node node) throws RepositoryException {
        if (node == null) {
            return null;
        }
        if (node.isNodeType("mix:versionable")) {
            return node;
        } 
        try {
            return findVersionableAncestor(node.getParent());
        } catch (ItemNotFoundException e) {
            // top-level
            return null;
        }
    }

    /**
     * Checkout the node if needed
     */
    protected void checkoutIfNecessary(Node node) throws RepositoryException {
        if (this.configuration.isAutoCheckout()) {
            Node versionableNode = findVersionableAncestor(node);
            if (versionableNode != null && !versionableNode.isCheckedOut()) {
                VersionManager versionManager = versionableNode.getSession().getWorkspace().getVersionManager();
                versionManager.checkout(versionableNode.getPath());
                if (this.importListener != null) {
                    this.importListener.onCheckout(versionableNode.getPath());
                }
            }
        }
    }

    @Override
    public void finish() throws RepositoryException {
        if (this.configuration.isMerge()) {
            Session session = this.createdRootNode.getSession();
            importedNodes.stream().flatMap(n -> {
                Set<String> iterable = getPeers(n,session);
                return StreamSupport.stream(iterable.spliterator(), false);
            }).filter(path -> !importedNodes.contains(path)).forEach(path -> removeNode(path,session));
            importedNodes.stream().flatMap(n -> {
                Set<String> iterable = getChildren(n,session);
                return StreamSupport.stream(iterable.spliterator(), false);
            }).filter(path -> !importedNodes.contains(path)).forEach(path -> removeNode(path,session));
        }
    }
    
    private Set<String> getPeers(String path,Session session) {
            try {
                log.debug("finding peers for {}", path);
                NodeIterator it = session.getNode(path).getParent().getNodes();
                Set<String> peers = new LinkedHashSet<>();
                while (it.hasNext()) {
                    String child = it.nextNode().getPath();
                    if (!child.equals(path)) {
                        peers.add(child);
                    }
                }
                return peers;
            } catch (RepositoryException e) {
                return Collections.emptySet();
            }
    }
    
    private Set<String> getChildren(String path,Session session) {
        try {
            log.debug("finding children for {}", path);
            NodeIterator it = session.getNode(path).getNodes();
            Set<String> peers = new LinkedHashSet<>();
            while (it.hasNext()) {
                String child = it.nextNode().getPath();
                peers.add(child);
            }
            return peers;
        } catch (RepositoryException e) {
            return Collections.emptySet();
        }
}
    
    private void removeNode(String item, Session session) {
        try {
            if (this.importListener != null) {
                this.importListener.onDelete(item);
            }
            log.debug("removing {}", item);
            session.removeItem(item);
            session.save();
        } catch (RepositoryException e) {
            log.warn("unable to remove node {}", item);
        }
        
    }

}
