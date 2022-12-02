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

import java.util.Collections;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

public class LocalPrivilege {
    private String privilegeName;
    private boolean allow;
    private boolean deny;
    private Set<LocalRestriction> allowRestrictions = Collections.emptySet();
    private Set<LocalRestriction> denyRestrictions = Collections.emptySet();
    private Privilege privilege;

    public LocalPrivilege(String privilege) {
        this.privilegeName = privilege;
    }

    public void checkPrivilege(AccessControlManager acm) throws RepositoryException {
        this.privilege = acm.privilegeFromName(this.privilegeName);
    }

    public Privilege getPrivilege() {
        return this.privilege;
    }

    public String getName() {
        return privilegeName;
    }

    public boolean isAllow() {
        return allow;
    }

    public boolean isDeny() {
        return deny;
    }

    public void setAllow(boolean allow) {
        this.allow = allow;
    }

    public void setDeny(boolean deny) {
        this.deny = deny;
    }

    public Set<LocalRestriction> getAllowRestrictions() {
        return allowRestrictions;
    }

    public void setAllowRestrictions(Set<LocalRestriction> allowRestrictions) {
        this.allowRestrictions = allowRestrictions;
    }

    public Set<LocalRestriction> getDenyRestrictions() {
        return denyRestrictions;
    }

    public void setDenyRestrictions(Set<LocalRestriction> denyRestrictions) {
        this.denyRestrictions = denyRestrictions;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("LocalPrivilege [privilege=");
        builder.append(privilegeName);
        builder.append(", allow=");
        builder.append(allow);
        builder.append(", deny=");
        builder.append(deny);
        builder.append(", allowRestrictions=");
        builder.append(allowRestrictions);
        builder.append(", denyRestrictions=");
        builder.append(denyRestrictions);
        builder.append("]");
        return builder.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (allow ? 1231 : 1237);
        result = prime * result + ((allowRestrictions == null) ? 0 : allowRestrictions.hashCode());
        result = prime * result + (deny ? 1231 : 1237);
        result = prime * result + ((denyRestrictions == null) ? 0 : denyRestrictions.hashCode());
        result = prime * result + ((privilegeName == null) ? 0 : privilegeName.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        LocalPrivilege other = (LocalPrivilege) obj;
        if (allow != other.allow)
            return false;
        if (allowRestrictions == null) {
            if (other.allowRestrictions != null)
                return false;
        } else if (!allowRestrictions.equals(other.allowRestrictions))
            return false;
        if (deny != other.deny)
            return false;
        if (denyRestrictions == null) {
            if (other.denyRestrictions != null)
                return false;
        } else if (!denyRestrictions.equals(other.denyRestrictions))
            return false;
        if (privilegeName == null) {
            if (other.privilegeName != null)
                return false;
        } else if (!privilegeName.equals(other.privilegeName))
            return false;
        return true;
    }

}
