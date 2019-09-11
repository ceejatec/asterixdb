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

package org.apache.asterix.runtime.evaluators.functions;

import org.apache.asterix.common.exceptions.ErrorCode;
import org.apache.asterix.common.exceptions.WarningUtil;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.EnumDeserializer;
import org.apache.asterix.runtime.exceptions.ExceptionUtil;
import org.apache.hyracks.algebricks.core.algebra.functions.FunctionIdentifier;
import org.apache.hyracks.algebricks.runtime.base.IEvaluatorContext;
import org.apache.hyracks.algebricks.runtime.base.IScalarEvaluator;
import org.apache.hyracks.api.exceptions.IWarningCollector;
import org.apache.hyracks.api.exceptions.SourceLocation;

public abstract class AbstractScalarEval implements IScalarEvaluator {
    protected final SourceLocation sourceLoc;
    protected final FunctionIdentifier functionIdentifier;

    public AbstractScalarEval(SourceLocation sourceLoc, FunctionIdentifier functionIdentifier) {
        this.sourceLoc = sourceLoc;
        this.functionIdentifier = functionIdentifier;
    }

    protected void handleTypeMismatchInput(IEvaluatorContext context, int inputPosition, ATypeTag expected,
            byte[] actualTypeBytes, int startOffset) {
        IWarningCollector warningCollector = context.getWarningCollector();
        if (warningCollector.shouldWarn()) {
            ATypeTag actual = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(actualTypeBytes[startOffset]);
            warningCollector.warn(WarningUtil.forAsterix(sourceLoc, ErrorCode.TYPE_MISMATCH_FUNCTION,
                    functionIdentifier, ExceptionUtil.indexToPosition(inputPosition), expected, actual));
        }
    }

    protected void handleTypeMismatchInput(IEvaluatorContext context, int inputPosition, byte[] expected,
            byte[] actualTypeBytes, int startOffset) {
        IWarningCollector warningCollector = context.getWarningCollector();
        if (warningCollector.shouldWarn()) {
            ATypeTag actual = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(actualTypeBytes[startOffset]);
            warningCollector.warn(WarningUtil.forAsterix(sourceLoc, ErrorCode.TYPE_MISMATCH_FUNCTION,
                    functionIdentifier, ExceptionUtil.indexToPosition(inputPosition),
                    ExceptionUtil.toExpectedTypeString(expected), actual));
        }
    }
}
