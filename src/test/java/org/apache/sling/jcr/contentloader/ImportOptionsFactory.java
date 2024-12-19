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

import org.jetbrains.annotations.NotNull;

public final class ImportOptionsFactory {

    public static final int NO_OPTIONS = 0;

    public static final int OVERWRITE_NODE = 0x1;

    public static final int OVERWRITE_PROPERTIES = 0x1 << 1;

    public static final int SYNCH_PROPERTIES = 0x1 << 2;

    public static final int SYNCH_NODES = 0x1 << 3;

    public static final int AUTO_CHECKOUT = 0x1 << 4;

    public static final int IGNORE_IMPORT_PROVIDER = 0x1 << 5;

    public static final int CHECK_IN = 0x1 << 6;

    public static final int REQUIRE_IMPORT_PROVIDER = 0x1 << 7;

    public static ImportOptions createImportOptions(int options) {
        return new ImportOptions() {
            @Override
            public boolean isOverwrite() {
                return (options & OVERWRITE_NODE) > NO_OPTIONS;
            }

            @Override
            public boolean isPropertyOverwrite() {
                return (options & OVERWRITE_PROPERTIES) > NO_OPTIONS;
            }

            @Override
            public boolean isAutoCheckout() {
                return (options & AUTO_CHECKOUT) > NO_OPTIONS;
            }

            @Override
            public boolean isCheckin() {
                return (options & CHECK_IN) > NO_OPTIONS;
            }

            @Override
            public boolean isIgnoredImportProvider(String extension) {
                return (options & IGNORE_IMPORT_PROVIDER) > NO_OPTIONS;
            }

            @Override
            public boolean isImportProviderRequired(@NotNull String name) {
                return (options & REQUIRE_IMPORT_PROVIDER) > NO_OPTIONS;
            }

            @Override
            public boolean isPropertyMerge() {
                return (options & SYNCH_PROPERTIES) > NO_OPTIONS;
            }

            @Override
            public boolean isMerge() {
                return (options & SYNCH_NODES) > NO_OPTIONS;
            }
        };
    }
}
