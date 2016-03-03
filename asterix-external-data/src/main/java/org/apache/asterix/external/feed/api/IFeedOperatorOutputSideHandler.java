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
package org.apache.asterix.external.feed.api;

import org.apache.asterix.external.feed.management.FeedId;
import org.apache.hyracks.api.comm.IFrameWriter;

/**
 * Provides for output-side buffering for a feed runtime.
 * Right now, we only have a single output side handler
 * and we can probably remove it completely.
 *              ______
 *             |      |
 * ============|core  |============
 * ============| op   |============
 *             |______|^^^^^^^^^^^^
 *                     Output Side
 *                       Handler
 *
 **/
public interface IFeedOperatorOutputSideHandler extends IFrameWriter {

    public enum Type {
        BASIC_FEED_OUTPUT_HANDLER,
        DISTRIBUTE_FEED_OUTPUT_HANDLER,
        COLLECT_TRANSFORM_FEED_OUTPUT_HANDLER
    }

    public FeedId getFeedId();

    public Type getType();

}