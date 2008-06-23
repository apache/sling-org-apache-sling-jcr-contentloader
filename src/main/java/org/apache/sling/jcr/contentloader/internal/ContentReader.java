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

import java.io.IOException;
import java.io.InputStream;

import javax.jcr.RepositoryException;

/**
 * The <code>ContentReader</code>
 * A content reader is provided by an {@link ImportProvider}.
 */
interface ContentReader {

    /**
     * Read the content from the input stream and create the
     * content throught the provided content creator.
     * The content reader should not close the input stream, this is
     * done by the calling component!
     * @param ins The input stream.
     * @throws IOException
     */
    void parse(InputStream ins, ContentCreator creator) throws IOException, RepositoryException;

}