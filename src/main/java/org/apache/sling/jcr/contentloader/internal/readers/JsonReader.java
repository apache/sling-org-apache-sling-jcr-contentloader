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

import static org.apache.sling.jcr.contentparser.impl.JsonTicksConverter.tickToDoubleQuote;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.ValueFormatException;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonValue.ValueType;

import org.apache.jackrabbit.oak.spi.security.authorization.restriction.CompositeRestrictionProvider;
import org.apache.jackrabbit.oak.spi.security.authorization.restriction.RestrictionDefinition;
import org.apache.jackrabbit.oak.spi.security.authorization.restriction.RestrictionProvider;
import org.apache.sling.jcr.contentloader.ContentCreator;
import org.apache.sling.jcr.contentloader.ContentReader;
import org.apache.sling.jcr.contentloader.LocalPrivilege;
import org.apache.sling.jcr.contentloader.LocalRestriction;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Component;

/**
 * The <code>JsonReader</code> Parses a Json document on content load and
 * creates the corresponding node structure with properties. Will not update
 * protected nodes and properties like rep:Policy and children.
 *
 * <pre>
 * Nodes, Properties and in fact complete subtrees may be described in JSON files
 * using the following skeleton structure (see http://www.json.org for information
 * on the syntax of JSON) :
 *
 * # the name of the node is taken from the name of the file without the .json ext.
 *   {
 *
 *     # optional primary node type, default &quot;nt:unstructured&quot;
 *     &quot;jcr:primaryType&quot;:&quot;sling:ScriptedComponent&quot;,
 *     # optional mixin node types as array
 *     &quot;jcr:mixinTypes&quot;: [ ],
 *
 *
 *       # &quot;properties&quot; are added as key value pairs, the name of the key being the name
 *       # of the property. The value is either the string property value, array for
 *       # multi-values or an object whose value[s] property denotes the property
 *       # value(s) and whose type property denotes the property type
 *       &quot;sling:contentClass&quot;: &quot;com.day.sling.jcr.test.Test&quot;,
 *       &quot;sampleMulti&quot;: [ &quot;v1&quot;, &quot;v2&quot; ],
 *       &quot;sampleStruct&quot;: 1,
 *       &quot;sampleStructMulti&quot;: [ 1, 2, 3 ],
 *
 *       # reference properties start with jcr:reference
 *       &quot;jcr:reference:sampleReference&quot;: &quot;/test/content&quot;,
 *
 *       # path propertie start with jcr:path
 *       &quot;jcr:path:sampleReference&quot;: &quot;/test/path&quot;,
 *
 *       # nested nodes are added as nested maps.
 *     &quot;sling:scripts&quot;:  {
 *
 *         &quot;jcr:primaryType&quot;: &quot;sling:ScriptList&quot;,
 *         &quot;script1&quot; :{
 *             &quot;primaryNodeType&quot;: &quot;sling:Script&quot;,
 *               &quot;sling:name&quot;: &quot;/test/content/jsp/start.jsp&quot;,
 *             &quot;sling:type&quot;: &quot;jsp&quot;,
 *             &quot;sling:glob&quot;: &quot;*&quot;
 *         }
 *     }
 *   }
 *
 * </pre>
 */
@Component(service = ContentReader.class, property = { Constants.SERVICE_VENDOR + "=The Apache Software Foundation",
        ContentReader.PROPERTY_EXTENSIONS + "=json", ContentReader.PROPERTY_TYPES + "=application/json" })
public class JsonReader implements ContentReader {

    private static final Pattern jsonDate = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}[-+]{1}[0-9]{2}[:]{0,1}[0-9]{2}$");
    private static final String REFERENCE = "jcr:reference:";
    private static final String PATH = "jcr:path:";
    private static final String NAME = "jcr:name:";
    private static final String URI = "jcr:uri:";

    protected static final Set<String> ignoredNames = new HashSet<>();
    static {
        ignoredNames.add("jcr:primaryType");
        ignoredNames.add("jcr:mixinTypes");
        ignoredNames.add("jcr:uuid");
        ignoredNames.add("jcr:baseVersion");
        ignoredNames.add("jcr:predecessors");
        ignoredNames.add("jcr:successors");
        ignoredNames.add("jcr:checkedOut");
        ignoredNames.add("jcr:created");
    }

    private static final Set<String> ignoredPrincipalPropertyNames = new HashSet<>();
    static {
        ignoredPrincipalPropertyNames.add("name");
        ignoredPrincipalPropertyNames.add("isgroup");
        ignoredPrincipalPropertyNames.add("members");
        ignoredPrincipalPropertyNames.add("dynamic");
        ignoredPrincipalPropertyNames.add("password");
    }
    private static final String SECURITY_PRINCIPLES = "security:principals";
    private static final String SECURITY_ACL = "security:acl";

    /**
     * @see org.apache.sling.jcr.contentloader.ContentReader#parse(java.net.URL,
     *      org.apache.sling.jcr.contentloader.ContentCreator)
     */
    @Override
    public void parse(java.net.URL url, ContentCreator contentCreator) throws IOException, RepositoryException {
        InputStream ins = null;
        try {
            ins = url.openStream();
            parse(ins, contentCreator);
        } finally {
            if (ins != null) {
                try {
                    ins.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
        }
    }

    @Override
    public void parse(InputStream ins, ContentCreator contentCreator) throws IOException, RepositoryException {
        String jsonString = toString(ins).trim();
        if (!jsonString.startsWith("{")) {
            jsonString = "{" + jsonString + "}";
        }
        Map<String, Object> config = new HashMap<>();
        config.put("org.apache.johnzon.supports-comments", true);
        try (jakarta.json.JsonReader reader = Json.createReaderFactory(config).createReader(new StringReader(tickToDoubleQuote(jsonString)))) {
            JsonObject json = reader.readObject();
            this.createNode(null, json, contentCreator);
            contentCreator.finish();
        } catch (JsonException je) {
            throw (IOException) new IOException(je.getMessage()).initCause(je);
        }
    }

    protected boolean handleSecurity(String n, Object o, ContentCreator contentCreator) throws RepositoryException {
        if (SECURITY_PRINCIPLES.equals(n)) {
            this.createPrincipals(o, contentCreator);
        } else if (SECURITY_ACL.equals(n)) {
            this.createAcl(o, contentCreator);
        } else {
            return false;
        }
        return true;
    }

    protected void writeChildren(JsonObject obj, ContentCreator contentCreator) throws RepositoryException {
        // add properties and nodes
        for (Map.Entry<String, JsonValue> entry : obj.entrySet()) {
            final String n = entry.getKey();
            // skip well known objects
            if (!ignoredNames.contains(n)) {
                Object o = entry.getValue();
                if (!handleSecurity(n, o, contentCreator)) {
                    if (o instanceof JsonObject) {
                        this.createNode(n, (JsonObject) o, contentCreator);
                    } else {
                        this.createProperty(n, o, contentCreator);
                    }
                }
            }
        }
    }

    protected void createNode(String name, JsonObject obj, ContentCreator contentCreator) throws RepositoryException {
        String primaryType = obj.getString("jcr:primaryType", null);

        String[] mixinTypes = null;
        Object mixinsObject = obj.get("jcr:mixinTypes");
        if (mixinsObject instanceof JsonArray) {
            JsonArray mixins = (JsonArray) mixinsObject;
            mixinTypes = new String[mixins.size()];
            for (int i = 0; i < mixinTypes.length; i++) {
                mixinTypes[i] = mixins.getString(i);
            }
        }

        contentCreator.createNode(name, primaryType, mixinTypes);
        writeChildren(obj, contentCreator);
        contentCreator.finishNode();
    }

    protected void createProperty(String name, Object value, ContentCreator contentCreator) throws RepositoryException {
        // assume simple value
        if (value instanceof JsonArray) {
            // multivalue
            final JsonArray array = (JsonArray) value;
            if (!array.isEmpty()) {
                final String[] values = new String[array.size()];
                for (int i = 0; i < values.length; i++) {
                    Object u = unbox(array.get(i));
                    values[i] = u == null ? null : u.toString();
                }
                final int propertyType = getType(name, unbox(array.get(0)));
                contentCreator.createProperty(getName(name), propertyType, values);
            } else {
                contentCreator.createProperty(getName(name), PropertyType.STRING, new String[0]);
            }
        } else if (value instanceof JsonValue) {
            // single value
            value = unbox(value);
            if (value != null) {
                contentCreator.createProperty(getName(name), getType(name, value), value.toString());
            }
        }
    }

    private Object unbox(Object o) {
        if (o instanceof JsonValue) {
            switch (((JsonValue) o).getValueType()) {
            case FALSE:
                return false;
            case TRUE:
                return true;
            case NULL:
                return null;
            case NUMBER:
                if (((JsonNumber) o).isIntegral()) {
                    return Long.valueOf(((JsonNumber) o).longValue());
                } else {
                    return Double.valueOf(((JsonNumber) o).doubleValue());
                }
            case STRING:
                return ((JsonString) o).getString();
            default:
                return o;
            }
        }
        return o;
    }

    private int getType(String name, Object object) {
        if (object == null) {
            return PropertyType.STRING;
        }
        if (object instanceof Double || object instanceof Float) {
            return PropertyType.DOUBLE;
        } else if (object instanceof Number) {
            return PropertyType.LONG;
        } else if (object instanceof Boolean) {
            return PropertyType.BOOLEAN;
        } else if (object instanceof String) {
            if (name.startsWith(REFERENCE))
                return PropertyType.REFERENCE;
            if (name.startsWith(PATH))
                return PropertyType.PATH;
            if (name.startsWith(NAME))
                return PropertyType.NAME;
            if (name.startsWith(URI))
                return PropertyType.URI;
            if (jsonDate.matcher((String) object).matches())
                return PropertyType.DATE;
        }

        // fall back to default
        return PropertyType.UNDEFINED;
    }

    private String getName(String name) {
        if (name.startsWith(REFERENCE))
            return name.substring(REFERENCE.length());
        if (name.startsWith(PATH))
            return name.substring(PATH.length());
        if (name.startsWith(NAME))
            return name.substring(NAME.length());
        if (name.startsWith(URI))
            return name.substring(URI.length());
        return name;
    }

    private String toString(InputStream ins) throws IOException {
        if (!ins.markSupported()) {
            ins = new BufferedInputStream(ins);
        }

        String encoding;
        ins.mark(5);
        int c = ins.read();
        if (c == '#') {
            // character encoding following
            StringBuilder buf = new StringBuilder();
            for (c = ins.read(); !Character.isWhitespace((char) c); c = ins.read()) {
                buf.append((char) c);
            }
            encoding = buf.toString();
        } else {
            ins.reset();
            encoding = "UTF-8";
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int rd;
        while ((rd = ins.read(buf)) >= 0) {
            bos.write(buf, 0, rd);
        }
        bos.close(); // just to comply with the contract

        return new String(bos.toByteArray(), encoding);
    }

    /**
     * Create or update one or more user and/or groups <code>
     *  {
     *     "security:principals" : [
     *        {
     *           "name":"owner",
     *           "isgroup":"true",
     *           "members":[],
     *           "dynamic":"true"
     *        }
     *     ],
     *  }
     *  </code>
     * 
     * @param obj
     *            Object
     * @param contentCreator
     *            Content creator
     * @throws RepositoryException
     *             Repository exception
     */
    protected void createPrincipals(Object obj, ContentCreator contentCreator) throws RepositoryException {
        if (obj instanceof JsonObject) {
            // single principal
            createPrincipal((JsonObject) obj, contentCreator);
        } else if (obj instanceof JsonArray) {
            // array of principals
            JsonArray jsonArray = (JsonArray) obj;
            for (int i = 0; i < jsonArray.size(); i++) {
                Object object = jsonArray.get(i);
                if (object instanceof JsonObject) {
                    createPrincipal((JsonObject) object, contentCreator);
                } else {
                    throw new JsonException("Unexpected data type in principals array: " + object.getClass().getName());
                }
            }
        }
    }

    /**
     * Create or update a user or group
     */
    private void createPrincipal(JsonObject json, ContentCreator contentCreator) throws RepositoryException {
        // create a principal
        String name = json.getString("name");
        boolean isGroup = json.getBoolean("isgroup", false);

        // collect the extra property names to assign to the new principal
        Map<String, Object> extraProps = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : json.entrySet()) {
            String propName = entry.getKey();
            if (!ignoredPrincipalPropertyNames.contains(propName)) {
                Object value = unbox(entry.getValue());
                extraProps.put(propName, value);
            }
        }

        if (isGroup) {
            String[] members = null;
            JsonArray membersJSONArray = (JsonArray) json.get("members");
            if (membersJSONArray != null) {
                members = new String[membersJSONArray.size()];
                for (int i = 0; i < members.length; i++) {
                    members[i] = membersJSONArray.getString(i);
                }
            }
            contentCreator.createGroup(name, members, extraProps);
        } else {
            String password = json.getString("password");
            contentCreator.createUser(name, password, extraProps);
        }
    }

    /**
     * Create or update one or more access control entries for the current node.
     *
     * <code>
     *  {
     *   "security:acl" : [
     *     	{
     *     		"principal" : "username1",
     *     		"granted" : [
     *      		"jcr:read",
     *      		"jcr:write"
     *     		],
     *     		"denied" : [
     *     		]
     *     	},
     *     	{
     *     		"principal" : "groupname1",
     *     		"granted" : [
     *      		"jcr:read",
     *      		"jcr:write"
     *     		]
     *     	}
     *   ]
     *  }
     *  </code>
     */
    private void createAcl(Object obj, ContentCreator contentCreator) throws RepositoryException {
        if (obj instanceof JsonObject) {
            // single ace
            createAce((JsonObject) obj, contentCreator);
        } else if (obj instanceof JsonArray) {
            // array of aces
            JsonArray jsonArray = (JsonArray) obj;
            for (int i = 0; i < jsonArray.size(); i++) {
                Object object = jsonArray.get(i);
                if (object instanceof JsonObject) {
                    createAce((JsonObject) object, contentCreator);
                } else {
                    throw new JsonException("Unexpected data type in acl array: " + object.getClass().getName());
                }
            }
        }
    }

    /**
     * Create or update an access control entry
     */
    private void createAce(JsonObject ace, ContentCreator contentCreator) throws RepositoryException {
        String principalID = ace.getString("principal");
        String order = ace.getString("order", null);

        // first start with an empty map
        Map<String, LocalPrivilege> privilegeToLocalPrivilegesMap = new LinkedHashMap<>();

        Node parentNode = contentCreator.getParent();
        ValueFactory vf = parentNode.getSession().getValueFactory();

        // Calculate a map of restriction names to the restriction definition.
        // Use for fast lookup during the calls below.
        Map<String, RestrictionDefinition> srMap = toSrMap(parentNode);

        // for backward compatibility, process the older syntax
        JsonArray granted = (JsonArray) ace.get("granted");
        JsonArray denied = (JsonArray) ace.get("denied");
        if (granted != null || denied != null) {
            JsonValue restrictions = ace.get("restrictions");
            Set<LocalRestriction> restrictionsSet = Collections.emptySet();
            if (restrictions instanceof JsonObject) {
                restrictionsSet = toLocalRestrictions((JsonObject)restrictions, srMap, vf);
            }

            if (granted != null) {
                for (int a = 0; a < granted.size(); a++) {
                    String privilegeName = granted.getString(a);
                    LocalPrivilege lp = privilegeToLocalPrivilegesMap.computeIfAbsent(privilegeName, LocalPrivilege::new);
                    lp.setAllow(true);
                    lp.setAllowRestrictions(restrictionsSet);
                }
            }

            if (denied != null) {
                for (int a = 0; a < denied.size(); a++) {
                    String privilegeName = denied.getString(a);
                    LocalPrivilege lp = privilegeToLocalPrivilegesMap.computeIfAbsent(privilegeName, LocalPrivilege::new);
                    lp.setDeny(true);
                    lp.setDenyRestrictions(restrictionsSet);
                }
            }
        }

        // now process the newer syntax
        JsonValue privileges = ace.get("privileges");
        if (privileges instanceof JsonObject) {
            JsonObject privilegesObj = (JsonObject)privileges;
            for (Entry<String, JsonValue> entry : privilegesObj.entrySet()) {
                String privilegeName = entry.getKey();
                JsonValue privilegeValue = entry.getValue();
                if (privilegeValue instanceof JsonObject) {
                    JsonObject privilegeValueObj = (JsonObject)privilegeValue;
                    JsonValue allow = privilegeValueObj.get("allow");
                    boolean isAllow = false;
                    Set<LocalRestriction> allowRestrictions = Collections.emptySet(); 
                    if (allow instanceof JsonObject) {
                        isAllow = true;
                        allowRestrictions = toLocalRestrictions((JsonObject)allow, srMap, vf);
                    } else if (JsonValue.TRUE.equals(allow)) {
                        isAllow = true;
                    }

                    JsonValue deny = privilegeValueObj.get("deny");
                    boolean isDeny = false;
                    Set<LocalRestriction> denyRestrictions = Collections.emptySet(); 
                    if (deny instanceof JsonObject) {
                        isDeny = true;
                        denyRestrictions = toLocalRestrictions((JsonObject)deny, srMap, vf);
                    } else if (JsonValue.TRUE.equals(deny)) {
                        isDeny = true;
                    }

                    if (isAllow || isDeny) {
                        LocalPrivilege lp = privilegeToLocalPrivilegesMap.computeIfAbsent(privilegeName, LocalPrivilege::new);
                        if (isAllow) {
                            lp.setAllow(true);
                            lp.setAllowRestrictions(allowRestrictions);
                        }
                        if (isDeny) {
                            lp.setDeny(true);
                            lp.setDenyRestrictions(denyRestrictions);
                        }
                    }
                }
            }
        }

        // do the work.
        contentCreator.createAce(principalID, new ArrayList<>(privilegeToLocalPrivilegesMap.values()), order);
    }

    /**
     * Calculate a map of restriction names to the restriction definition
     * 
     * @param parentNode the node the restrictions are for
     */
    protected Map<String, RestrictionDefinition> toSrMap(Node parentNode)
            throws RepositoryException {
        // lazy initialized map for quick lookup when processing restrictions
        Map<String, RestrictionDefinition> supportedRestrictionsMap = new HashMap<>();

        RestrictionProvider compositeRestrictionProvider = null;
        Set<RestrictionProvider> restrictionProviders = new HashSet<>();

        Bundle bundle = FrameworkUtil.getBundle(getClass());
        if (bundle != null) {
            BundleContext bundleContext = bundle.getBundleContext();
            Collection<ServiceReference<RestrictionProvider>> serviceReferences = null;
            try {
                serviceReferences = bundleContext.getServiceReferences(RestrictionProvider.class, null);
                for (ServiceReference<RestrictionProvider> serviceReference : serviceReferences) {
                    RestrictionProvider service = bundleContext.getService(serviceReference);
                    restrictionProviders.add(service);
                }
                compositeRestrictionProvider = CompositeRestrictionProvider.newInstance(restrictionProviders);

                // populate the map
                Set<RestrictionDefinition> supportedRestrictions = compositeRestrictionProvider
                        .getSupportedRestrictions(parentNode.getPath());
                for (RestrictionDefinition restrictionDefinition : supportedRestrictions) {
                    supportedRestrictionsMap.put(restrictionDefinition.getName(), restrictionDefinition);
                }
            } catch (InvalidSyntaxException e) {
                throw new RepositoryException(e);
            } finally {
                if (serviceReferences != null) {
                    for (ServiceReference<RestrictionProvider> serviceReference : serviceReferences) {
                        bundleContext.ungetService(serviceReference);
                    }
                }
            }
        }
        return supportedRestrictionsMap;
    }

    /**
     * Construct a LocalRestriction using data from the json object
     * 
     * @param allowOrDenyObj the json object
     * @param srMap map of restriction names to the restriction definition
     * @param vf the ValueFactory
     */
    protected Set<LocalRestriction> toLocalRestrictions(JsonObject allowOrDenyObj,
            Map<String, RestrictionDefinition> srMap,
            ValueFactory vf) throws RepositoryException {
        Set<LocalRestriction> restrictions = new HashSet<>();
        for (Entry<String, JsonValue> restrictionEntry : allowOrDenyObj.entrySet()) {
            String restrictionName = restrictionEntry.getKey();
            RestrictionDefinition rd = srMap.get(restrictionName);
            if (rd == null) {
                // illegal restriction name?
                throw new JsonException("Invalid or not supported restriction name was supplied: " + restrictionName);
            }

            boolean multival = rd.getRequiredType().isArray();
            int restrictionType = rd.getRequiredType().tag();

            LocalRestriction lr = null;
            JsonValue jsonValue = restrictionEntry.getValue();

            if (multival) {
                if (jsonValue.getValueType() == ValueType.ARRAY) {
                    JsonArray jsonArray = (JsonArray) jsonValue;
                    int size = jsonArray.size();
                    Value[] values = new Value[size];
                    for (int i = 0; i < size; i++) {
                        values[i] = toValue(vf, jsonArray.get(i), restrictionType);
                    }
                    lr = new LocalRestriction(restrictionName, values);
                } else {
                    Value v = toValue(vf, jsonValue, restrictionType);
                    lr = new LocalRestriction(restrictionName, new Value[] { v });
                }
            } else {
                if (jsonValue.getValueType() == ValueType.ARRAY) {
                    JsonArray jsonArray = (JsonArray) jsonValue;
                    int size = jsonArray.size();
                    if (size == 1) {
                        Value v = toValue(vf, jsonArray.get(0), restrictionType);
                        lr = new LocalRestriction(restrictionName, v);
                    } else if (size > 1) {
                        throw new JsonException(
                                "Unexpected multi value array data found for single-value restriction value for name: "
                                        + restrictionName);
                    }
                } else {
                    Value v = toValue(vf, jsonValue, restrictionType);
                    lr = new LocalRestriction(restrictionName, v);
                }
            }
            if (lr != null) {
                restrictions.add(lr);
            }
        }
        return restrictions;
    }

    /**
     * Attempt to convert the JsonValue to the equivalent JCR Value object
     * 
     * @param factory
     *            the JCR value factory
     * @param jsonValue
     *            the JSON value to convert
     * @param restrictionType
     *            a hint for the expected property type of the value
     * @return the Value if converted or null otherwise
     * @throws ValueFormatException
     */
    private Value toValue(ValueFactory factory, JsonValue jsonValue, int restrictionType) throws ValueFormatException {
        Value value = null;
        ValueType valueType = jsonValue.getValueType();
        switch (valueType) {
        case TRUE:
            value = factory.createValue(false);
            break;
        case FALSE:
            value = factory.createValue(false);
            break;
        case NUMBER:
            JsonNumber jsonNumber = (JsonNumber) jsonValue;
            if (jsonNumber.isIntegral()) {
                value = factory.createValue(jsonNumber.longValue());
            } else {
                value = factory.createValue(jsonNumber.doubleValue());
            }
            break;
        case STRING:
            value = factory.createValue(((JsonString) jsonValue).getString(), restrictionType);
            break;
        case NULL:
        case ARRAY:
        case OBJECT:
        default:
            // illegal JSON?
            break;
        }

        return value;
    }
}
