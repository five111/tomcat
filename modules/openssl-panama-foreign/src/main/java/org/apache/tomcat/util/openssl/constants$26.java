/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Generated by jextract

package org.apache.tomcat.util.openssl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import jdk.incubator.foreign.*;
import static jdk.incubator.foreign.ValueLayout.*;
class constants$26 {

    static final FunctionDescriptor OCSP_cert_to_id$FUNC = FunctionDescriptor.of(ADDRESS,
        ADDRESS,
        ADDRESS,
        ADDRESS
    );
    static final MethodHandle OCSP_cert_to_id$MH = RuntimeHelper.downcallHandle(
        "OCSP_cert_to_id",
        constants$26.OCSP_cert_to_id$FUNC, false
    );
    static final FunctionDescriptor OCSP_request_add0_id$FUNC = FunctionDescriptor.of(ADDRESS,
        ADDRESS,
        ADDRESS
    );
    static final MethodHandle OCSP_request_add0_id$MH = RuntimeHelper.downcallHandle(
        "OCSP_request_add0_id",
        constants$26.OCSP_request_add0_id$FUNC, false
    );
    static final FunctionDescriptor OCSP_response_status$FUNC = FunctionDescriptor.of(JAVA_INT,
        ADDRESS
    );
    static final MethodHandle OCSP_response_status$MH = RuntimeHelper.downcallHandle(
        "OCSP_response_status",
        constants$26.OCSP_response_status$FUNC, false
    );
    static final FunctionDescriptor OCSP_response_get1_basic$FUNC = FunctionDescriptor.of(ADDRESS,
        ADDRESS
    );
    static final MethodHandle OCSP_response_get1_basic$MH = RuntimeHelper.downcallHandle(
        "OCSP_response_get1_basic",
        constants$26.OCSP_response_get1_basic$FUNC, false
    );
    static final FunctionDescriptor OCSP_resp_get0$FUNC = FunctionDescriptor.of(ADDRESS,
        ADDRESS,
        JAVA_INT
    );
    static final MethodHandle OCSP_resp_get0$MH = RuntimeHelper.downcallHandle(
        "OCSP_resp_get0",
        constants$26.OCSP_resp_get0$FUNC, false
    );
    static final FunctionDescriptor OCSP_resp_find$FUNC = FunctionDescriptor.of(JAVA_INT,
        ADDRESS,
        ADDRESS,
        JAVA_INT
    );
    static final MethodHandle OCSP_resp_find$MH = RuntimeHelper.downcallHandle(
        "OCSP_resp_find",
        constants$26.OCSP_resp_find$FUNC, false
    );
}


