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

import javax.jcr.Value;

import java.util.Arrays;

import org.jetbrains.annotations.NotNull;

public class LocalRestriction {
    private String restriction;
    private boolean multival;
    private Value[] values;

    public LocalRestriction(@NotNull String restriction, Value[] values) {
        this.restriction = restriction;
        this.multival = true;
        this.values = values;
    }

    public LocalRestriction(@NotNull String restriction, Value value) {
        super();
        this.restriction = restriction;
        this.multival = false;
        this.values = value == null ? null : new Value[] {value};
    }

    public String getName() {
        return restriction;
    }

    public boolean isMultiValue() {
        return multival;
    }

    public Value getValue() {
        Value v = null;
        if (values != null && values.length > 0) {
            v = values[0];
        }
        return v;
    }

    public Value[] getValues() {
        return values;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("LocalRestriction [restriction=");
        builder.append(restriction);
        builder.append(", multival=");
        builder.append(multival);
        builder.append(", values=");
        builder.append(Arrays.toString(values));
        builder.append("]");
        return builder.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (multival ? 1231 : 1237);
        result = prime * result + ((restriction == null) ? 0 : restriction.hashCode());
        result = prime * result + Arrays.hashCode(values);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        LocalRestriction other = (LocalRestriction) obj;
        if (multival != other.multival) return false;
        if (restriction == null) {
            if (other.restriction != null) return false;
        } else if (!restriction.equals(other.restriction)) return false;
        return Arrays.equals(values, other.values);
    }
}
