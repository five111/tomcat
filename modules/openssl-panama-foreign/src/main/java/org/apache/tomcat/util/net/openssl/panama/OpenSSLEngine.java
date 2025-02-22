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
package org.apache.tomcat.util.net.openssl.panama;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionBindingEvent;
import javax.net.ssl.SSLSessionBindingListener;
import javax.net.ssl.SSLSessionContext;

import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.NativeSymbol;
import jdk.incubator.foreign.ResourceScope;
import jdk.incubator.foreign.SegmentAllocator;
import jdk.incubator.foreign.ValueLayout;

import static org.apache.tomcat.util.openssl.openssl_h.*;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteBufferUtils;
import org.apache.tomcat.util.net.Constants;
import org.apache.tomcat.util.net.SSLUtil;
import org.apache.tomcat.util.net.openssl.ciphers.OpenSSLCipherConfigurationParser;
import org.apache.tomcat.util.res.StringManager;

/**
 * Implements a {@link SSLEngine} using
 * <a href="https://www.openssl.org/docs/crypto/BIO_s_bio.html#EXAMPLE">OpenSSL
 * BIO abstractions</a>.
 */
public final class OpenSSLEngine extends SSLEngine implements SSLUtil.ProtocolInfo {

    private static final Log logger = LogFactory.getLog(OpenSSLEngine.class);
    private static final StringManager sm = StringManager.getManager(OpenSSLEngine.class);

    private static final Certificate[] EMPTY_CERTIFICATES = new Certificate[0];

    public static final Set<String> AVAILABLE_CIPHER_SUITES;

    public static final Set<String> IMPLEMENTED_PROTOCOLS_SET;

    private static final MethodHandle openSSLCallbackInfoHandle;
    private static final MethodHandle openSSLCallbackVerifyHandle;

    private static final FunctionDescriptor openSSLCallbackInfoFunctionDescriptor =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private static final FunctionDescriptor openSSLCallbackVerifyFunctionDescriptor =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            openSSLCallbackInfoHandle = lookup.findVirtual(OpenSSLEngine.class, "openSSLCallbackInfo",
                    MethodType.methodType(void.class, MemoryAddress.class, int.class, int.class));
            openSSLCallbackVerifyHandle = lookup.findVirtual(OpenSSLEngine.class, "openSSLCallbackVerify",
                    MethodType.methodType(int.class, int.class, MemoryAddress.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        OpenSSLLifecycleListener.initLibrary();

        final Set<String> availableCipherSuites = new LinkedHashSet<>(128);
        try (var scope = ResourceScope.newConfinedScope()) {
            var allocator = SegmentAllocator.nativeAllocator(scope);
            var sslCtx = SSL_CTX_new(TLS_server_method());
            try {
                SSL_CTX_set_options(sslCtx, SSL_OP_ALL());
                SSL_CTX_set_cipher_list(sslCtx, allocator.allocateUtf8String("ALL"));
                var ssl = SSL_new(sslCtx);
                SSL_set_accept_state(ssl);
                try {
                    for (String c : getCiphers(ssl)) {
                        // Filter out bad input.
                        if (c == null || c.length() == 0 || availableCipherSuites.contains(c)) {
                            continue;
                        }
                        availableCipherSuites.add(OpenSSLCipherConfigurationParser.openSSLToJsse(c));
                    }
                } finally {
                    SSL_free(ssl);
                }
            } finally {
                SSL_CTX_free(sslCtx);
            }
        } catch (Exception e) {
            logger.warn(sm.getString("engine.ciphersFailure"), e);
        }
        AVAILABLE_CIPHER_SUITES = Collections.unmodifiableSet(availableCipherSuites);

        HashSet<String> protocols = new HashSet<>();
        protocols.add(Constants.SSL_PROTO_SSLv2Hello);
        protocols.add(Constants.SSL_PROTO_SSLv2);
        protocols.add(Constants.SSL_PROTO_SSLv3);
        protocols.add(Constants.SSL_PROTO_TLSv1);
        protocols.add(Constants.SSL_PROTO_TLSv1_1);
        protocols.add(Constants.SSL_PROTO_TLSv1_2);
        protocols.add(Constants.SSL_PROTO_TLSv1_3);
        IMPLEMENTED_PROTOCOLS_SET = Collections.unmodifiableSet(protocols);
    }

    private static String[] getCiphers(MemoryAddress ssl) {
        MemoryAddress sk = SSL_get_ciphers(ssl);
        int len = OPENSSL_sk_num(sk);
        if (len <= 0) {
            return null;
        }
        ArrayList<String> ciphers = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            MemoryAddress cipher = OPENSSL_sk_value(sk, i);
            MemoryAddress cipherName = SSL_CIPHER_get_name(cipher);
            ciphers.add(cipherName.getUtf8String(0));
        }
        return ciphers.toArray(new String[0]);
    }

    private static final int MAX_PLAINTEXT_LENGTH = 16 * 1024; // 2^14
    private static final int MAX_COMPRESSED_LENGTH = MAX_PLAINTEXT_LENGTH + 1024;
    private static final int MAX_CIPHERTEXT_LENGTH = MAX_COMPRESSED_LENGTH + 1024;

    // Protocols
    static final int VERIFY_DEPTH = 10;

    // Header (5) + Data (2^14) + Compression (1024) + Encryption (1024) + MAC (20) + Padding (256)
    static final int MAX_ENCRYPTED_PACKET_LENGTH = MAX_CIPHERTEXT_LENGTH + 5 + 20 + 256;

    static final int MAX_ENCRYPTION_OVERHEAD_LENGTH = MAX_ENCRYPTED_PACKET_LENGTH - MAX_PLAINTEXT_LENGTH;

    enum ClientAuthMode {
        NONE,
        OPTIONAL,
        REQUIRE,
    }

    private static final String INVALID_CIPHER = "SSL_NULL_WITH_NULL_NULL";

    private final OpenSSLState state;
    private final Cleanable cleanable;

    private enum Accepted { NOT, IMPLICIT, EXPLICIT }
    private Accepted accepted = Accepted.NOT;
    private enum PHAState { NONE, START, COMPLETE }
    private PHAState phaState = PHAState.NONE;
    private boolean handshakeFinished;
    private int currentHandshake;
    private int handshakeCount = 0;
    private boolean receivedShutdown;
    private volatile boolean destroyed;

    // Use an invalid cipherSuite until the handshake is completed
    // See http://docs.oracle.com/javase/7/docs/api/javax/net/ssl/SSLEngine.html#getSession()
    private volatile String version;
    private volatile String cipher;
    private volatile String applicationProtocol;

    private volatile Certificate[] peerCerts;
    @Deprecated
    private volatile javax.security.cert.X509Certificate[] x509PeerCerts;
    private volatile ClientAuthMode clientAuth = ClientAuthMode.NONE;

    // SSL Engine status variables
    private boolean isInboundDone;
    private boolean isOutboundDone;
    private boolean engineClosed;
    private boolean sendHandshakeError = false;

    private final boolean clientMode;
    private final boolean noOcspCheck;
    private final String fallbackApplicationProtocol;
    private final OpenSSLSessionContext sessionContext;
    private final boolean alpn;
    private final boolean initialized;
    private final boolean certificateVerificationOptionalNoCA;
    private final int certificateVerificationDepth;

    private int certificateVerifyMode = 0;

    private String selectedProtocol = null;

    private final OpenSSLSession session;

    /**
     * Creates a new instance
     *
     * @param cleaner   Used to clean up references to instances before they are
     *                  garbage collected
     * @param sslCtx an OpenSSL {@code SSL_CTX} object
     * @param fallbackApplicationProtocol the fallback application protocol
     * @param clientMode {@code true} if this is used for clients, {@code false}
     * otherwise
     * @param sessionContext the {@link OpenSSLSessionContext} this
     * {@link SSLEngine} belongs to.
     * @param alpn {@code true} if alpn should be used, {@code false}
     * otherwise
     * @param initialized {@code true} if this instance gets its protocol,
     * cipher and client verification from the {@code SSL_CTX} {@code sslCtx}
     * @param certificateVerificationDepth Certificate verification depth
     * @param certificateVerificationOptionalNoCA Skip CA verification in
     *   optional mode
     */
    OpenSSLEngine(Cleaner cleaner, MemoryAddress sslCtx, String fallbackApplicationProtocol,
            boolean clientMode, OpenSSLSessionContext sessionContext, boolean alpn,
            boolean initialized, int certificateVerificationDepth,
            boolean certificateVerificationOptionalNoCA, boolean noOcspCheck) {
        if (sslCtx == null) {
            throw new IllegalArgumentException(sm.getString("engine.noSSLContext"));
        }
        ResourceScope scope = ResourceScope.newSharedScope();
        var allocator = SegmentAllocator.nativeAllocator(scope);
        session = new OpenSSLSession();
        var ssl = SSL_new(sslCtx);
        this.certificateVerificationDepth = certificateVerificationDepth;
        // Set ssl_info_callback
        NativeSymbol openSSLCallbackInfo = CLinker.systemCLinker().upcallStub(openSSLCallbackInfoHandle.bindTo(this),
                openSSLCallbackInfoFunctionDescriptor, scope);
        SSL_set_info_callback(ssl, openSSLCallbackInfo);
        if (clientMode) {
            SSL_set_connect_state(ssl);
        } else {
            SSL_set_accept_state(ssl);
        }
        SSL_set_verify_result(ssl, X509_V_OK());
        var internalBIOPointer = allocator.allocate(ValueLayout.ADDRESS);
        var networkBIOPointer = allocator.allocate(ValueLayout.ADDRESS);
        BIO_new_bio_pair(internalBIOPointer, 0, networkBIOPointer, 0);
        var internalBIO = internalBIOPointer.get(ValueLayout.ADDRESS, 0);
        var networkBIO = networkBIOPointer.get(ValueLayout.ADDRESS, 0);
        SSL_set_bio(ssl, internalBIO, internalBIO);
        state = new OpenSSLState(scope, ssl, networkBIO);
        cleanable = cleaner.register(this, state);
        this.fallbackApplicationProtocol = fallbackApplicationProtocol;
        this.clientMode = clientMode;
        this.sessionContext = sessionContext;
        this.alpn = alpn;
        this.initialized = initialized;
        this.certificateVerificationOptionalNoCA = certificateVerificationOptionalNoCA;
        this.noOcspCheck = noOcspCheck;
    }

    @Override
    public String getNegotiatedProtocol() {
        return selectedProtocol;
    }

    /**
     * Destroys this engine.
     */
    public synchronized void shutdown() {
        if (!destroyed) {
            destroyed = true;
            cleanable.clean();
            // internal errors can cause shutdown without marking the engine closed
            isInboundDone = isOutboundDone = engineClosed = true;
        }
    }

    /**
     * Write plain text data to the OpenSSL internal BIO
     *
     * Calling this function with src.remaining == 0 is undefined.
     * @throws SSLException if the OpenSSL error check fails
     */
    private int writePlaintextData(final MemoryAddress ssl, final ByteBuffer src) throws SSLException {
        clearLastError();
        final int pos = src.position();
        final int limit = src.limit();
        final int len = Math.min(limit - pos, MAX_PLAINTEXT_LENGTH);
        final int sslWrote;

        if (src.isDirect()) {
            sslWrote = SSL_write(ssl, MemorySegment.ofByteBuffer(src), len);
            if (sslWrote > 0) {
                src.position(pos + sslWrote);
                return sslWrote;
            } else {
                checkLastError();
            }
        } else {
            try (var scope = ResourceScope.newConfinedScope()) {
                var allocator = SegmentAllocator.nativeAllocator(scope);
                MemorySegment bufSegment = allocator.allocateArray(ValueLayout.JAVA_BYTE, len);
                MemorySegment.copy(src.array(), pos, bufSegment, ValueLayout.JAVA_BYTE, 0, len);
                sslWrote = SSL_write(ssl, bufSegment, len);
                if (sslWrote > 0) {
                    src.position(pos + sslWrote);
                    return sslWrote;
                } else {
                    checkLastError();
                }
            }
        }

        return 0;
    }

    /**
     * Write encrypted data to the OpenSSL network BIO.
     * @throws SSLException if the OpenSSL error check fails
     */
    private int writeEncryptedData(final MemoryAddress networkBIO, final ByteBuffer src) throws SSLException {
        clearLastError();
        final int pos = src.position();
        final int len = src.remaining();
        if (src.isDirect()) {
            final int netWrote = BIO_write(networkBIO, MemorySegment.ofByteBuffer(src), len);
            if (netWrote > 0) {
                src.position(pos + netWrote);
                return netWrote;
            } else {
                checkLastError();
            }
        } else {
            // This uses unsafe and does not need to be used: the connector should be configured with direct buffers
            ByteBuffer buf = ByteBuffer.allocateDirect(len);
            try {
                buf.put(src);
                buf.flip();
                final int netWrote = BIO_write(networkBIO, MemorySegment.ofByteBuffer(buf), len);
                if (netWrote > 0) {
                    src.position(pos + netWrote);
                    return netWrote;
                } else {
                    src.position(pos);
                    checkLastError();
                }
            } finally {
                buf.clear();
                ByteBufferUtils.cleanDirectBuffer(buf);
            }
        }

        return 0;
    }

    /**
     * Read plain text data from the OpenSSL internal BIO
     * @throws SSLException if the OpenSSL error check fails
     */
    private int readPlaintextData(final MemoryAddress ssl, final ByteBuffer dst) throws SSLException {
        clearLastError();
        final int pos = dst.position();

        if (dst.isDirect()) {
            final int len = dst.remaining();
            final int sslRead = SSL_read(ssl, MemorySegment.ofByteBuffer(dst), len);
            if (sslRead > 0) {
                dst.position(dst.position() + sslRead);
                return sslRead;
            } else {
                checkLastError();
            }
        } else {
            final int limit = dst.limit();
            final int len = Math.min(MAX_ENCRYPTED_PACKET_LENGTH, limit - pos);
            try (var scope = ResourceScope.newConfinedScope()) {
                var allocator = SegmentAllocator.nativeAllocator(scope);
                MemorySegment bufSegment = allocator.allocateArray(ValueLayout.JAVA_BYTE, len);
                final int sslRead = SSL_read(ssl, bufSegment, len);
                if (sslRead > 0) {
                    MemorySegment.copy(bufSegment, ValueLayout.JAVA_BYTE, 0, dst.array(), pos, sslRead);
                    dst.position(dst.position() + sslRead);
                    return sslRead;
                } else {
                    checkLastError();
                }
            }
        }

        return 0;
    }

    /**
     * Read encrypted data from the OpenSSL network BIO
     * @throws SSLException if the OpenSSL error check fails
     */
    private int readEncryptedData(final MemoryAddress networkBIO, final ByteBuffer dst, final int pending) throws SSLException {
        clearLastError();
        if (dst.isDirect()) {
            final int pos = dst.position();
            final int bioRead = BIO_read(networkBIO, MemorySegment.ofByteBuffer(dst), pending);
            if (bioRead > 0) {
                dst.position(pos + bioRead);
                return bioRead;
            } else {
                checkLastError();
            }
        } else {
            // This uses unsafe and does not need to be used: the connector should be configured with direct buffers
            final ByteBuffer buf = ByteBuffer.allocateDirect(pending);
            try {
                final int bioRead = BIO_read(networkBIO, MemorySegment.ofByteBuffer(buf), pending);
                if (bioRead > 0) {
                    buf.limit(bioRead);
                    int oldLimit = dst.limit();
                    dst.limit(dst.position() + bioRead);
                    dst.put(buf);
                    dst.limit(oldLimit);
                    return bioRead;
                } else {
                    checkLastError();
                }
            } finally {
                buf.clear();
                ByteBufferUtils.cleanDirectBuffer(buf);
            }
        }

        return 0;
    }

    @Override
    public synchronized SSLEngineResult wrap(final ByteBuffer[] srcs, final int offset, final int length, final ByteBuffer dst) throws SSLException {
        // Check to make sure the engine has not been closed
        if (destroyed) {
            return new SSLEngineResult(SSLEngineResult.Status.CLOSED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0);
        }

        // Throw required runtime exceptions
        if (srcs == null || dst == null) {
            throw new IllegalArgumentException(sm.getString("engine.nullBuffer"));
        }
        if (offset >= srcs.length || offset + length > srcs.length) {
            throw new IndexOutOfBoundsException(sm.getString("engine.invalidBufferArray",
                    Integer.toString(offset), Integer.toString(length),
                    Integer.toString(srcs.length)));
        }
        if (dst.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        // Prepare OpenSSL to work in server mode and receive handshake
        if (accepted == Accepted.NOT) {
            beginHandshakeImplicitly();
        }

        // In handshake or close_notify stages, check if call to wrap was made
        // without regard to the handshake status.
        SSLEngineResult.HandshakeStatus handshakeStatus = getHandshakeStatus();

        if ((!handshakeFinished || engineClosed) && handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
            return new SSLEngineResult(getEngineStatus(), SSLEngineResult.HandshakeStatus.NEED_UNWRAP, 0, 0);
        }

        int bytesProduced = 0;
        int pendingNet;

        // Check for pending data in the network BIO
        pendingNet = (int) BIO_ctrl_pending(state.networkBIO);
        if (pendingNet > 0) {
            // Do we have enough room in destination to write encrypted data?
            int capacity = dst.remaining();
            if (capacity < pendingNet) {
                return new SSLEngineResult(SSLEngineResult.Status.BUFFER_OVERFLOW, handshakeStatus, 0, 0);
            }

            // Write the pending data from the network BIO into the dst buffer
            try {
                bytesProduced = readEncryptedData(state.networkBIO, dst, pendingNet);
            } catch (Exception e) {
                throw new SSLException(e);
            }

            // If isOutboundDone is set, then the data from the network BIO
            // was the close_notify message -- we are not required to wait
            // for the receipt the peer's close_notify message -- shutdown.
            if (isOutboundDone) {
                shutdown();
            }

            return new SSLEngineResult(getEngineStatus(), getHandshakeStatus(), 0, bytesProduced);
        }

        // There was no pending data in the network BIO -- encrypt any application data
        int bytesConsumed = 0;
        int endOffset = offset + length;
        for (int i = offset; i < endOffset; ++i) {
            final ByteBuffer src = srcs[i];
            if (src == null) {
                throw new IllegalArgumentException(sm.getString("engine.nullBufferInArray"));
            }
            while (src.hasRemaining()) {

                int bytesWritten = 0;
                // Write plain text application data to the SSL engine
                try {
                    bytesWritten = writePlaintextData(state.ssl, src);
                    bytesConsumed += bytesWritten;
                } catch (Exception e) {
                    throw new SSLException(e);
                }

                if (bytesWritten == 0) {
                    throw new IllegalStateException(sm.getString("engine.failedToWriteBytes"));
                }

                // Check to see if the engine wrote data into the network BIO
                pendingNet = (int) BIO_ctrl_pending(state.networkBIO);
                if (pendingNet > 0) {
                    // Do we have enough room in dst to write encrypted data?
                    int capacity = dst.remaining();
                    if (capacity < pendingNet) {
                        return new SSLEngineResult(
                                SSLEngineResult.Status.BUFFER_OVERFLOW, getHandshakeStatus(), bytesConsumed, bytesProduced);
                    }

                    // Write the pending data from the network BIO into the dst buffer
                    try {
                        bytesProduced += readEncryptedData(state.networkBIO, dst, pendingNet);
                    } catch (Exception e) {
                        throw new SSLException(e);
                    }

                    return new SSLEngineResult(getEngineStatus(), getHandshakeStatus(), bytesConsumed, bytesProduced);
                }
            }
        }

        return new SSLEngineResult(getEngineStatus(), getHandshakeStatus(), bytesConsumed, bytesProduced);
    }

    @Override
    public synchronized SSLEngineResult unwrap(final ByteBuffer src, final ByteBuffer[] dsts, final int offset, final int length) throws SSLException {
        // Check to make sure the engine has not been closed
        if (destroyed) {
            return new SSLEngineResult(SSLEngineResult.Status.CLOSED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, 0, 0);
        }

        // Throw required runtime exceptions
        if (src == null || dsts == null) {
            throw new IllegalArgumentException(sm.getString("engine.nullBuffer"));
        }
        if (offset >= dsts.length || offset + length > dsts.length) {
            throw new IndexOutOfBoundsException(sm.getString("engine.invalidBufferArray",
                    Integer.toString(offset), Integer.toString(length),
                    Integer.toString(dsts.length)));
        }
        int capacity = 0;
        final int endOffset = offset + length;
        for (int i = offset; i < endOffset; i++) {
            ByteBuffer dst = dsts[i];
            if (dst == null) {
                throw new IllegalArgumentException(sm.getString("engine.nullBufferInArray"));
            }
            if (dst.isReadOnly()) {
                throw new ReadOnlyBufferException();
            }
            capacity += dst.remaining();
        }

        // Prepare OpenSSL to work in server mode and receive handshake
        if (accepted == Accepted.NOT) {
            beginHandshakeImplicitly();
        }

        // In handshake or close_notify stages, check if call to unwrap was made
        // without regard to the handshake status.
        SSLEngineResult.HandshakeStatus handshakeStatus = getHandshakeStatus();
        if ((!handshakeFinished || engineClosed) && handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            return new SSLEngineResult(getEngineStatus(), SSLEngineResult.HandshakeStatus.NEED_WRAP, 0, 0);
        }

        int len = src.remaining();

        // protect against protocol overflow attack vector
        if (len > MAX_ENCRYPTED_PACKET_LENGTH) {
            isInboundDone = true;
            isOutboundDone = true;
            engineClosed = true;
            shutdown();
            throw new SSLException(sm.getString("engine.oversizedPacket"));
        }

        // Write encrypted data to network BIO
        int written = 0;
        try {
            written = writeEncryptedData(state.networkBIO, src);
        } catch (Exception e) {
            throw new SSLException(e);
        }

        // There won't be any application data until we're done handshaking
        //
        // We first check handshakeFinished to eliminate the overhead of extra JNI call if possible.
        int pendingApp = pendingReadableBytesInSSL();
        if (!handshakeFinished) {
            pendingApp = 0;
        }
        int bytesProduced = 0;
        int idx = offset;
        // Do we have enough room in dsts to write decrypted data?
        if (capacity == 0) {
            return new SSLEngineResult(SSLEngineResult.Status.BUFFER_OVERFLOW, getHandshakeStatus(), written, 0);
        }

        while (pendingApp > 0) {
            if (idx == endOffset) {
                // Destination buffer state changed (no remaining space although
                // capacity is still available), so break loop with an error
                throw new IllegalStateException(sm.getString("engine.invalidDestinationBuffersState"));
            }
            // Write decrypted data to dsts buffers
            while (idx < endOffset) {
                ByteBuffer dst = dsts[idx];
                if (!dst.hasRemaining()) {
                    idx++;
                    continue;
                }

                if (pendingApp <= 0) {
                    break;
                }

                int bytesRead;
                try {
                    bytesRead = readPlaintextData(state.ssl, dst);
                } catch (Exception e) {
                    throw new SSLException(e);
                }

                if (bytesRead == 0) {
                    // This should not be possible. pendingApp is positive
                    // therefore the read should have read at least one byte.
                    throw new IllegalStateException(sm.getString("engine.failedToReadAvailableBytes"));
                }

                bytesProduced += bytesRead;
                pendingApp -= bytesRead;
                capacity -= bytesRead;

                if (!dst.hasRemaining()) {
                    idx++;
                }
            }
            if (capacity == 0) {
                break;
            } else if (pendingApp == 0) {
                pendingApp = pendingReadableBytesInSSL();
            }
        }

        // Check to see if we received a close_notify message from the peer
        if (!receivedShutdown && (SSL_get_shutdown(state.ssl) & SSL_RECEIVED_SHUTDOWN()) == SSL_RECEIVED_SHUTDOWN()) {
            receivedShutdown = true;
            closeOutbound();
            closeInbound();
        }

        if (bytesProduced == 0 && (written == 0 || (written > 0 && !src.hasRemaining() && handshakeFinished))) {
            return new SSLEngineResult(SSLEngineResult.Status.BUFFER_UNDERFLOW, getHandshakeStatus(), written, 0);
        } else {
            return new SSLEngineResult(getEngineStatus(), getHandshakeStatus(), written, bytesProduced);
        }
    }

    private int pendingReadableBytesInSSL()
            throws SSLException {
        // NOTE: Calling a fake read is necessary before calling pendingReadableBytesInSSL because
        // SSL_pending will return 0 if OpenSSL has not started the current TLS record
        // See https://www.openssl.org/docs/manmaster/man3/SSL_pending.html
        clearLastError();
        int lastPrimingReadResult = SSL_read(state.ssl, MemoryAddress.NULL, 0); // priming read
        // check if SSL_read returned <= 0. In this case we need to check the error and see if it was something
        // fatal.
        if (lastPrimingReadResult <= 0) {
            checkLastError();
        }
        int pendingReadableBytesInSSL = SSL_pending(state.ssl);

        // TLS 1.0 needs additional handling
        if (Constants.SSL_PROTO_TLSv1.equals(version) && lastPrimingReadResult == 0 &&
                pendingReadableBytesInSSL == 0) {
            // Perform another priming read
            lastPrimingReadResult = SSL_read(state.ssl, MemoryAddress.NULL, 0);
            if (lastPrimingReadResult <= 0) {
                checkLastError();
            }
            pendingReadableBytesInSSL = SSL_pending(state.ssl);
        }

        return pendingReadableBytesInSSL;
    }

    @Override
    public Runnable getDelegatedTask() {
        // Currently, we do not delegate SSL computation tasks
        return null;
    }

    @Override
    public synchronized void closeInbound() throws SSLException {
        if (isInboundDone) {
            return;
        }

        isInboundDone = true;
        engineClosed = true;

        shutdown();

        if (accepted != Accepted.NOT && !receivedShutdown) {
            throw new SSLException(sm.getString("engine.inboundClose"));
        }
    }

    @Override
    public synchronized boolean isInboundDone() {
        return isInboundDone || engineClosed;
    }

    @Override
    public synchronized void closeOutbound() {
        if (isOutboundDone) {
            return;
        }

        isOutboundDone = true;
        engineClosed = true;

        if (accepted != Accepted.NOT && !destroyed) {
            int mode = SSL_get_shutdown(state.ssl);
            if ((mode & SSL_SENT_SHUTDOWN()) != SSL_SENT_SHUTDOWN()) {
                SSL_shutdown(state.ssl);
            }
        } else {
            // engine closing before initial handshake
            shutdown();
        }
    }

    @Override
    public synchronized boolean isOutboundDone() {
        return isOutboundDone;
    }

    @Override
    public String[] getSupportedCipherSuites() {
        Set<String> availableCipherSuites = AVAILABLE_CIPHER_SUITES;
        return availableCipherSuites.toArray(new String[0]);
    }

    @Override
    public synchronized String[] getEnabledCipherSuites() {
        if (destroyed) {
            return new String[0];
        }
        String[] enabled = getCiphers(state.ssl);
        if (enabled == null) {
            return new String[0];
        } else {
            for (int i = 0; i < enabled.length; i++) {
                String mapped = OpenSSLCipherConfigurationParser.openSSLToJsse(enabled[i]);
                if (mapped != null) {
                    enabled[i] = mapped;
                }
            }
            return enabled;
        }
    }

    @Override
    public synchronized void setEnabledCipherSuites(String[] cipherSuites) {
        if (initialized) {
            return;
        }
        if (cipherSuites == null) {
            throw new IllegalArgumentException(sm.getString("engine.nullCipherSuite"));
        }
        if (destroyed) {
            return;
        }
        final StringBuilder buf = new StringBuilder();
        for (String cipherSuite : cipherSuites) {
            if (cipherSuite == null) {
                break;
            }
            String converted = OpenSSLCipherConfigurationParser.jsseToOpenSSL(cipherSuite);
            if (!AVAILABLE_CIPHER_SUITES.contains(cipherSuite)) {
                logger.debug(sm.getString("engine.unsupportedCipher", cipherSuite, converted));
            }
            if (converted != null) {
                cipherSuite = converted;
            }

            buf.append(cipherSuite);
            buf.append(':');
        }

        if (buf.length() == 0) {
            throw new IllegalArgumentException(sm.getString("engine.emptyCipherSuite"));
        }
        buf.setLength(buf.length() - 1);

        final String cipherSuiteSpec = buf.toString();
        try {
            SSL_set_cipher_list(state.ssl, SegmentAllocator.nativeAllocator(state.scope)
                    .allocateUtf8String(cipherSuiteSpec));
        } catch (Exception e) {
            throw new IllegalStateException(sm.getString("engine.failedCipherSuite", cipherSuiteSpec), e);
        }
    }

    @Override
    public String[] getSupportedProtocols() {
        return IMPLEMENTED_PROTOCOLS_SET.toArray(new String[0]);
    }

    @Override
    public synchronized String[] getEnabledProtocols() {
        if (destroyed) {
            return new String[0];
        }
        List<String> enabled = new ArrayList<>();
        // Seems like there is no way to explicitly disable SSLv2Hello in OpenSSL so it is always enabled
        enabled.add(Constants.SSL_PROTO_SSLv2Hello);
        long opts = SSL_get_options(state.ssl);
        if ((opts & SSL_OP_NO_TLSv1()) == 0) {
            enabled.add(Constants.SSL_PROTO_TLSv1);
        }
        if ((opts & SSL_OP_NO_TLSv1_1()) == 0) {
            enabled.add(Constants.SSL_PROTO_TLSv1_1);
        }
        if ((opts & SSL_OP_NO_TLSv1_2()) == 0) {
            enabled.add(Constants.SSL_PROTO_TLSv1_2);
        }
        if ((opts & SSL_OP_NO_TLSv1_3()) == 0) {
            enabled.add(Constants.SSL_PROTO_TLSv1_3);
        }
        if ((opts & SSL_OP_NO_SSLv2()) == 0) {
            enabled.add(Constants.SSL_PROTO_SSLv2);
        }
        if ((opts & SSL_OP_NO_SSLv3()) == 0) {
            enabled.add(Constants.SSL_PROTO_SSLv3);
        }
        int size = enabled.size();
        if (size == 0) {
            return new String[0];
        } else {
            return enabled.toArray(new String[size]);
        }
    }

    @Override
    public synchronized void setEnabledProtocols(String[] protocols) {
        if (initialized) {
            return;
        }
        if (protocols == null) {
            // This is correct from the API docs
            throw new IllegalArgumentException();
        }
        if (destroyed) {
            return;
        }
        boolean sslv2 = false;
        boolean sslv3 = false;
        boolean tlsv1 = false;
        boolean tlsv1_1 = false;
        boolean tlsv1_2 = false;
        boolean tlsv1_3 = false;
        for (String p : protocols) {
            if (!IMPLEMENTED_PROTOCOLS_SET.contains(p)) {
                throw new IllegalArgumentException(sm.getString("engine.unsupportedProtocol", p));
            }
            if (p.equals(Constants.SSL_PROTO_SSLv2)) {
                sslv2 = true;
            } else if (p.equals(Constants.SSL_PROTO_SSLv3)) {
                sslv3 = true;
            } else if (p.equals(Constants.SSL_PROTO_TLSv1)) {
                tlsv1 = true;
            } else if (p.equals(Constants.SSL_PROTO_TLSv1_1)) {
                tlsv1_1 = true;
            } else if (p.equals(Constants.SSL_PROTO_TLSv1_2)) {
                tlsv1_2 = true;
            } else if (p.equals(Constants.SSL_PROTO_TLSv1_3)) {
                tlsv1_3 = true;
            }
        }
        // Enable all and then disable what we not want
        SSL_set_options(state.ssl, SSL_OP_ALL());

        if (!sslv2) {
            SSL_set_options(state.ssl, SSL_OP_NO_SSLv2());
        }
        if (!sslv3) {
            SSL_set_options(state.ssl, SSL_OP_NO_SSLv3());
        }
        if (!tlsv1) {
            SSL_set_options(state.ssl, SSL_OP_NO_TLSv1());
        }
        if (!tlsv1_1) {
            SSL_set_options(state.ssl, SSL_OP_NO_TLSv1_1());
        }
        if (!tlsv1_2) {
            SSL_set_options(state.ssl, SSL_OP_NO_TLSv1_2());
        }
        if (!tlsv1_3) {
            SSL_set_options(state.ssl, SSL_OP_NO_TLSv1_3());
        }
    }

    @Override
    public SSLSession getSession() {
        return session;
    }

    @Override
    public synchronized void beginHandshake() throws SSLException {
        if (engineClosed || destroyed) {
            throw new SSLException(sm.getString("engine.engineClosed"));
        }
        switch (accepted) {
        case NOT:
            handshake();
            accepted = Accepted.EXPLICIT;
            break;
        case IMPLICIT:
            // A user did not start handshake by calling this method by themselves,
            // but handshake has been started already by wrap() or unwrap() implicitly.
            // Because it's the user's first time to call this method, it is unfair to
            // raise an exception.  From the user's standpoint, they never asked for
            // renegotiation.

            accepted = Accepted.EXPLICIT; // Next time this method is invoked by the user, we should raise an exception.
            break;
        case EXPLICIT:
            renegotiate();
            break;
        }
    }

    private byte[] getPeerCertificate() {
        var allocator = SegmentAllocator.nativeAllocator(state.scope);
        MemoryAddress/*(X509*)*/ x509 = SSL_get_peer_certificate(state.ssl);
        MemorySegment bufPointer = allocator.allocate(ValueLayout.ADDRESS, MemoryAddress.NULL);
        int length = i2d_X509(x509, bufPointer);
        if (length <= 0) {
            return null;
        }
        MemoryAddress buf = bufPointer.get(ValueLayout.ADDRESS, 0);
        byte[] certificate = MemorySegment.ofAddressNative(buf, length, state.scope).toArray(ValueLayout.JAVA_BYTE);
        X509_free(x509);
        CRYPTO_free(buf, OPENSSL_FILE(), OPENSSL_LINE()); // OPENSSL_free macro
        return certificate;
    }

    private byte[][] getPeerCertChain() {
        MemoryAddress/*STACK_OF(X509)*/ sk = SSL_get_peer_cert_chain(state.ssl);
        int len = OPENSSL_sk_num(sk);
        if (len <= 0) {
            return null;
        }
        byte[][] certificateChain = new byte[len][];
        var allocator = SegmentAllocator.nativeAllocator(state.scope);
        for (int i = 0; i < len; i++) {
            MemoryAddress/*(X509*)*/ x509 = OPENSSL_sk_value(sk, i);
            MemorySegment bufPointer = allocator.allocate(ValueLayout.ADDRESS, MemoryAddress.NULL);
            int length = i2d_X509(x509, bufPointer);
            if (length < 0) {
                certificateChain[i] = new byte[0];
                continue;
            }
            MemoryAddress buf = bufPointer.get(ValueLayout.ADDRESS, 0);
            byte[] certificate = MemorySegment.ofAddressNative(buf, length, state.scope).toArray(ValueLayout.JAVA_BYTE);
            certificateChain[i] = certificate;
            CRYPTO_free(buf, OPENSSL_FILE(), OPENSSL_LINE()); // OPENSSL_free macro
        }
        return certificateChain;
    }

    private String getProtocolNegotiated() {
        var allocator = SegmentAllocator.nativeAllocator(state.scope);
        MemorySegment lenAddress = allocator.allocate(ValueLayout.JAVA_INT, 0);
        MemorySegment protocolPointer = allocator.allocate(ValueLayout.ADDRESS, MemoryAddress.NULL);
        SSL_get0_alpn_selected(state.ssl, protocolPointer, lenAddress);
        if (MemoryAddress.NULL.equals(protocolPointer.address())) {
            SSL_get0_next_proto_negotiated(state.ssl, protocolPointer, lenAddress);
        }
        if (MemoryAddress.NULL.equals(protocolPointer.address())) {
            return null;
        }
        int len = lenAddress.get(ValueLayout.JAVA_INT, 0);
        if (len == 0) {
            return null;
        }
        MemoryAddress protocolAddress = protocolPointer.get(ValueLayout.ADDRESS, 0);
        byte[] name = MemorySegment.ofAddressNative(protocolAddress, len, state.scope).toArray(ValueLayout.JAVA_BYTE);
        if (logger.isDebugEnabled()) {
            logger.debug("Protocol negotiated [" + new String(name) + "]");
        }
        return new String(name);
    }

    private void beginHandshakeImplicitly() throws SSLException {
        handshake();
        accepted = Accepted.IMPLICIT;
    }

    private void handshake() throws SSLException {
        currentHandshake = handshakeCount;
        clearLastError();
        int code = SSL_do_handshake(state.ssl);
        if (code <= 0) {
            checkLastError();
        } else {
            if (alpn) {
                selectedProtocol = getProtocolNegotiated();
            }
            session.lastAccessedTime = System.currentTimeMillis();
            // if SSL_do_handshake returns > 0 it means the handshake was finished. This means we can update
            // handshakeFinished directly and so eliminate unnecessary calls to SSL.isInInit(...)
            handshakeFinished = true;
        }
    }

    private synchronized void renegotiate() throws SSLException {
        if (logger.isDebugEnabled()) {
            logger.debug("Start renegotiate");
        }
        clearLastError();
        int code;
        if (SSL_get_version(state.ssl).getUtf8String(0).equals(Constants.SSL_PROTO_TLSv1_3)) {
            phaState = PHAState.START;
            code = SSL_verify_client_post_handshake(state.ssl);
        } else {
            code = SSL_renegotiate(state.ssl);
        }
        if (code <= 0) {
            checkLastError();
        }
        handshakeFinished = false;
        peerCerts = null;
        x509PeerCerts = null;
        currentHandshake = handshakeCount;
        int code2 = SSL_do_handshake(state.ssl);
        if (code2 <= 0) {
            checkLastError();
        }
    }

    private void checkLastError() throws SSLException {
        String sslError = getLastError();
        if (sslError != null) {
            // Many errors can occur during handshake and need to be reported
            if (!handshakeFinished) {
                sendHandshakeError = true;
            } else {
                throw new SSLException(sslError);
            }
        }
    }


    /**
     * Clear out any errors, but log a warning.
     */
    private static void clearLastError() {
        getLastError();
    }

    /**
     * Many calls to SSL methods do not check the last error. Those that do
     * check the last error need to ensure that any previously ignored error is
     * cleared prior to the method call else errors may be falsely reported.
     * Ideally, before any SSL_read, SSL_write, clearLastError should always
     * be called, and getLastError should be called after on any negative or
     * zero result.
     * @return the first error in the stack
     */
    private static String getLastError() {
        String sslError = null;
        long error = ERR_get_error();
        if (error != SSL_ERROR_NONE()) {
            try (var scope = ResourceScope.newConfinedScope()) {
                var allocator = SegmentAllocator.nativeAllocator(scope);
                do {
                    // Loop until getLastErrorNumber() returns SSL_ERROR_NONE
                    var buf = allocator.allocateArray(ValueLayout.JAVA_BYTE, new byte[128]);
                    ERR_error_string(error, buf);
                    String err = buf.getUtf8String(0);
                    if (sslError == null) {
                        sslError = err;
                    }
                    if (logger.isDebugEnabled()) {
                        logger.debug(sm.getString("engine.openSSLError", Long.toString(error), err));
                    }
                } while ((error = ERR_get_error()) != SSL_ERROR_NONE());
            }
        }
        return sslError;
    }

    private SSLEngineResult.Status getEngineStatus() {
        return engineClosed ? SSLEngineResult.Status.CLOSED : SSLEngineResult.Status.OK;
    }

    @Override
    public synchronized SSLEngineResult.HandshakeStatus getHandshakeStatus() {
        if (accepted == Accepted.NOT || destroyed) {
            return SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
        }

        // Check if we are in the initial handshake phase
        if (!handshakeFinished) {

            // There is pending data in the network BIO -- call wrap
            if (sendHandshakeError || BIO_ctrl_pending(state.networkBIO) != 0) {
                if (sendHandshakeError) {
                    // After a last wrap, consider it is going to be done
                    sendHandshakeError = false;
                    currentHandshake++;
                }
                return SSLEngineResult.HandshakeStatus.NEED_WRAP;
            }

            /*
             * Tomcat Native stores a count of the completed handshakes in the
             * SSL instance and increments it every time a handshake is
             * completed. Comparing the handshake count when the handshake
             * started to the current handshake count enables this code to
             * detect when the handshake has completed.
             *
             * Obtaining client certificates after the connection has been
             * established requires additional checks. We need to trigger
             * additional reads until the certificates have been read but we
             * don't know how many reads we will need as it depends on both
             * client and network behaviour.
             *
             * The additional reads are triggered by returning NEED_UNWRAP
             * rather than FINISHED. This allows the standard I/O code to be
             * used.
             *
             * For TLSv1.2 and below, the handshake completes before the
             * renegotiation. We therefore use SSL.renegotiatePending() to
             * check on the current status of the renegotiation and return
             * NEED_UNWRAP until it completes which means the client
             * certificates will have been read from the client.
             *
             * For TLSv1.3, Tomcat Native sets a flag when post handshake
             * authentication is started and updates it once the client
             * certificate has been received. We therefore use
             * SSL.getPostHandshakeAuthInProgress() to check the current status
             * and return NEED_UNWRAP until that methods indicates that PHA is
             * no longer in progress.
             */

            // No pending data to be sent to the peer
            // Check to see if we have finished handshaking
            if (handshakeCount != currentHandshake && SSL_renegotiate_pending(state.ssl) == 0 &&
                    (phaState != PHAState.START)) {
                if (alpn) {
                    selectedProtocol = getProtocolNegotiated();
                }
                session.lastAccessedTime = System.currentTimeMillis();
                version = SSL_get_version(state.ssl).getUtf8String(0);
                handshakeFinished = true;
                return SSLEngineResult.HandshakeStatus.FINISHED;
            }

            // No pending data
            // Still handshaking / renegotiation / post-handshake auth pending
            // Must be waiting on the peer to send more data
            return SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
        }

        // Check if we are in the shutdown phase
        if (engineClosed) {
            // Waiting to send the close_notify message
            if (BIO_ctrl_pending(state.networkBIO) != 0) {
                return SSLEngineResult.HandshakeStatus.NEED_WRAP;
            }

            // Must be waiting to receive the close_notify message
            return SSLEngineResult.HandshakeStatus.NEED_UNWRAP;
        }

        return SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
    }

    @Override
    public void setUseClientMode(boolean clientMode) {
        if (clientMode != this.clientMode) {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public boolean getUseClientMode() {
        return clientMode;
    }

    @Override
    public void setNeedClientAuth(boolean b) {
        setClientAuth(b ? ClientAuthMode.REQUIRE : ClientAuthMode.NONE);
    }

    @Override
    public boolean getNeedClientAuth() {
        return clientAuth == ClientAuthMode.REQUIRE;
    }

    @Override
    public void setWantClientAuth(boolean b) {
        setClientAuth(b ? ClientAuthMode.OPTIONAL : ClientAuthMode.NONE);
    }

    @Override
    public boolean getWantClientAuth() {
        return clientAuth == ClientAuthMode.OPTIONAL;
    }

    private static final int OPTIONAL_NO_CA = 3;

    private void setClientAuth(ClientAuthMode mode) {
        if (clientMode) {
            return;
        }
        synchronized (this) {
            if (clientAuth == mode) {
                return;
            }
            certificateVerifyMode = switch (mode) {
                case NONE -> SSL_VERIFY_NONE();
                case REQUIRE -> SSL_VERIFY_FAIL_IF_NO_PEER_CERT();
                case OPTIONAL -> certificateVerificationOptionalNoCA ? OPTIONAL_NO_CA : SSL_VERIFY_PEER();
            };
            // SSL.setVerify(state.ssl, value, certificateVerificationDepth);
            // Set int verify_callback(int preverify_ok, X509_STORE_CTX *x509_ctx) callback
            NativeSymbol openSSLCallbackVerify =
                    CLinker.systemCLinker().upcallStub(openSSLCallbackVerifyHandle.bindTo(this),
                    openSSLCallbackVerifyFunctionDescriptor, state.scope);
            int value = switch (mode) {
                case NONE -> SSL_VERIFY_NONE();
                case REQUIRE -> SSL_VERIFY_PEER() | SSL_VERIFY_FAIL_IF_NO_PEER_CERT();
                case OPTIONAL -> SSL_VERIFY_PEER();
            };
            SSL_set_verify(state.ssl, value, openSSLCallbackVerify);
            clientAuth = mode;
        }
    }

    public synchronized void openSSLCallbackInfo(MemoryAddress ssl, int where, int ret) {
        if (0 != (where & SSL_CB_HANDSHAKE_DONE())) {
            handshakeCount++;
        }
    }

    public synchronized int openSSLCallbackVerify(int preverify_ok, MemoryAddress /*X509_STORE_CTX*/ x509ctx) {
        if (logger.isDebugEnabled()) {
            logger.debug("Verification in engine with mode [" + certificateVerifyMode + "] for " + state.ssl);
        }
        int ok = preverify_ok;
        int errnum = X509_STORE_CTX_get_error(x509ctx);
        int errdepth = X509_STORE_CTX_get_error_depth(x509ctx);
        phaState = PHAState.COMPLETE;
        if (certificateVerifyMode == -1 /*SSL_CVERIFY_UNSET*/ || certificateVerifyMode == SSL_VERIFY_NONE()) {
            return 1;
        }
        /*SSL_VERIFY_ERROR_IS_OPTIONAL(errnum) -> ((errnum == X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT)
                || (errnum == X509_V_ERR_SELF_SIGNED_CERT_IN_CHAIN)
                || (errnum == X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY)
                || (errnum == X509_V_ERR_CERT_UNTRUSTED)
                || (errnum == X509_V_ERR_UNABLE_TO_VERIFY_LEAF_SIGNATURE))*/
        boolean verifyErrorIsOptional = (errnum == X509_V_ERR_DEPTH_ZERO_SELF_SIGNED_CERT())
                || (errnum == X509_V_ERR_SELF_SIGNED_CERT_IN_CHAIN())
                || (errnum == X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY())
                || (errnum == X509_V_ERR_CERT_UNTRUSTED())
                || (errnum == X509_V_ERR_UNABLE_TO_VERIFY_LEAF_SIGNATURE());
        if (verifyErrorIsOptional && (certificateVerifyMode == OPTIONAL_NO_CA)) {
            ok = 1;
            SSL_set_verify_result(state.ssl, X509_V_OK());
        }
        /*
         * Expired certificates vs. "expired" CRLs: by default, OpenSSL
         * turns X509_V_ERR_CRL_HAS_EXPIRED into a "certificate_expired(45)"
         * SSL alert, but that's not really the message we should convey to the
         * peer (at the very least, it's confusing, and in many cases, it's also
         * inaccurate, as the certificate itself may very well not have expired
         * yet). We set the X509_STORE_CTX error to something which OpenSSL's
         * s3_both.c:ssl_verify_alarm_type() maps to SSL_AD_CERTIFICATE_UNKNOWN,
         * i.e. the peer will receive a "certificate_unknown(46)" alert.
         * We do not touch errnum, though, so that later on we will still log
         * the "real" error, as returned by OpenSSL.
         */
        if (ok == 0 && errnum == X509_V_ERR_CRL_HAS_EXPIRED()) {
            X509_STORE_CTX_set_error(x509ctx, -1);
        }

        // OCSP
        if (!noOcspCheck && (ok > 0)) {
            /* If there was an optional verification error, it's not
             * possible to perform OCSP validation since the issuer may be
             * missing/untrusted.  Fail in that case.
             */
            if (verifyErrorIsOptional) {
                if (certificateVerifyMode != OPTIONAL_NO_CA) {
                    X509_STORE_CTX_set_error(x509ctx, X509_V_ERR_APPLICATION_VERIFICATION());
                    errnum = X509_V_ERR_APPLICATION_VERIFICATION();
                    ok = 0;
                }
            } else {
                int ocspResponse = processOCSP(x509ctx);
                if (ocspResponse == V_OCSP_CERTSTATUS_REVOKED()) {
                    ok = 0;
                    errnum = X509_STORE_CTX_get_error(x509ctx);
                } else if (ocspResponse == V_OCSP_CERTSTATUS_UNKNOWN()) {
                    errnum = X509_STORE_CTX_get_error(x509ctx);
                    if (errnum <= 0) {
                        ok = 0;
                    }
                }
            }
        }

        if (errdepth > certificateVerificationDepth) {
            // Certificate Verification: Certificate Chain too long
            ok = 0;
        }
        return ok;
    }

    static int processOCSP(MemoryAddress /*X509_STORE_CTX*/ x509ctx) {
        int ocspResponse = V_OCSP_CERTSTATUS_UNKNOWN();
        // ocspResponse = ssl_verify_OCSP(x509_ctx);
        MemoryAddress x509 = X509_STORE_CTX_get_current_cert(x509ctx);
        if (!MemoryAddress.NULL.equals(x509)) {
            // No need to check cert->valid, because ssl_verify_OCSP() only
            // is called if OpenSSL already successfully verified the certificate
            // (parameter "ok" in SSL_callback_SSL_verify() must be true).
            if (X509_check_issued(x509, x509) == X509_V_OK()) {
                // don't do OCSP checking for valid self-issued certs
                X509_STORE_CTX_set_error(x509ctx, X509_V_OK());
            } else {
                // If we can't get the issuer, we cannot perform OCSP verification
                MemoryAddress issuer = X509_STORE_CTX_get0_current_issuer(x509ctx);
                if (!MemoryAddress.NULL.equals(issuer)) {
                    // sslutils.c ssl_ocsp_request(x509, issuer, x509ctx);
                    int nid = X509_get_ext_by_NID(x509, NID_info_access(), -1);
                    if (nid >= 0) {
                        try (var scope = ResourceScope.newConfinedScope()) {
                            MemoryAddress ext = X509_get_ext(x509, nid);
                            MemoryAddress os = X509_EXTENSION_get_data(ext);
                            int length = ASN1_STRING_length(os);
                            MemoryAddress data = ASN1_STRING_get0_data(os);
                            // ocsp_urls = decode_OCSP_url(os);
                            byte[] asn1String = MemorySegment.ofAddressNative(data, length, scope).toArray(ValueLayout.JAVA_BYTE);
                            Asn1Parser parser = new Asn1Parser(asn1String);
                            // Parse the byte sequence
                            ArrayList<String> urls = new ArrayList<>();
                            try {
                                parseOCSPURLs(parser, urls);
                            } catch (Exception e) {
                                logger.error(sm.getString("engine.ocspParseError"), e);
                            }
                            if (!urls.isEmpty()) {
                                // Use OpenSSL to build OCSP request
                                for (String urlString : urls) {
                                    try {
                                        URL url = new URL(urlString);
                                        ocspResponse = processOCSPRequest(url, issuer, x509, x509ctx, scope);
                                        if (logger.isDebugEnabled()) {
                                            logger.debug("OCSP response for URL: " + urlString + " was " + ocspResponse);
                                        }
                                    } catch (MalformedURLException e) {
                                        logger.warn(sm.getString("engine.invalidOCSPURL", urlString));
                                    }
                                    if (ocspResponse != V_OCSP_CERTSTATUS_UNKNOWN()) {
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return ocspResponse;
    }

    private static final int ASN1_SEQUENCE = 0x30;
    private static final int ASN1_OID      = 0x06;
    private static final int ASN1_STRING   = 0x86;
    private static final byte[] OCSP_OID = {0x2b, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x01};

    private static void parseOCSPURLs(Asn1Parser parser, ArrayList<String> urls) {
        while (!parser.eof()) {
            int tag = parser.peekTag();
            if (tag == ASN1_SEQUENCE) {
                parser.parseTag(ASN1_SEQUENCE);
                parser.parseFullLength();
            } else if (tag == ASN1_OID) {
                parser.parseTag(ASN1_OID);
                int oidLen = parser.parseLength();
                byte[] oid = new byte[oidLen];
                parser.parseBytes(oid);
                if (Arrays.compareUnsigned(oid, 0, OCSP_OID.length, OCSP_OID, 0, OCSP_OID.length) == 0) {
                    parser.parseTag(ASN1_STRING);
                    int urlLen = parser.parseLength();
                    byte[] url = new byte[urlLen];
                    parser.parseBytes(url);
                    urls.add(new String(url));
                }
            } else {
                return;
            }
        }
    }

    private static int processOCSPRequest(URL url, MemoryAddress issuer, MemoryAddress x509,
            MemoryAddress /*X509_STORE_CTX*/ x509ctx, ResourceScope scope) {
        MemoryAddress ocspRequest = MemoryAddress.NULL;
        MemoryAddress ocspResponse = MemoryAddress.NULL;
        MemoryAddress id = MemoryAddress.NULL;
        MemoryAddress ocspOneReq = MemoryAddress.NULL;
        HttpURLConnection connection = null;
        MemoryAddress basicResponse = MemoryAddress.NULL;
        MemoryAddress certId = MemoryAddress.NULL;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            var allocator = SegmentAllocator.nativeAllocator(scope);
            ocspRequest = OCSP_REQUEST_new();
            if (MemoryAddress.NULL.equals(ocspRequest)) {
                return V_OCSP_CERTSTATUS_UNKNOWN();
            }
            id = OCSP_cert_to_id(MemoryAddress.NULL, x509, issuer);
            if (MemoryAddress.NULL.equals(id)) {
                return V_OCSP_CERTSTATUS_UNKNOWN();
            }
            ocspOneReq = OCSP_request_add0_id(ocspRequest, id);
            if (MemoryAddress.NULL.equals(ocspOneReq)) {
                return V_OCSP_CERTSTATUS_UNKNOWN();
            }
            MemorySegment bufPointer = allocator.allocate(ValueLayout.ADDRESS, MemoryAddress.NULL);
            int requestLength = i2d_OCSP_REQUEST(ocspRequest, bufPointer);
            if (requestLength <= 0) {
                return V_OCSP_CERTSTATUS_UNKNOWN();
            }
            MemoryAddress buf = bufPointer.get(ValueLayout.ADDRESS, 0);
            // HTTP request with the following header
            // POST urlPath HTTP/1.1
            // Host: urlHost:urlPort
            // Content-Type: application/ocsp-request
            // Content-Length: ocspRequestData.length
            byte[] ocspRequestData = MemorySegment.ofAddressNative(buf, requestLength, scope).toArray(ValueLayout.JAVA_BYTE);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(requestLength);
            connection.setRequestProperty("Content-Type", "application/ocsp-request");
            connection.connect();
            connection.getOutputStream().write(ocspRequestData);
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return V_OCSP_CERTSTATUS_UNKNOWN();
            }
            InputStream is = connection.getInputStream();
            int read = 0;
            byte[] responseBuf = new byte[1024];
            while ((read = is.read(responseBuf)) > 0) {
                baos.write(responseBuf, 0, read);
            }
            byte[] responseData = baos.toByteArray();
            var nativeResponseData = allocator.allocateArray(ValueLayout.JAVA_BYTE, responseData);
            var nativeResponseDataPointer = allocator.allocate(ValueLayout.ADDRESS, nativeResponseData);
            ocspResponse = d2i_OCSP_RESPONSE(MemoryAddress.NULL, nativeResponseDataPointer, responseData.length);
            if (!MemoryAddress.NULL.equals(ocspResponse)) {
                if (OCSP_response_status(ocspResponse) == OCSP_RESPONSE_STATUS_SUCCESSFUL()) {
                    basicResponse = OCSP_response_get1_basic(ocspResponse);
                    certId = OCSP_cert_to_id(MemoryAddress.NULL, x509, issuer);
                    if (MemoryAddress.NULL.equals(certId)) {
                        return V_OCSP_CERTSTATUS_UNKNOWN();
                    }
                    // Find by serial number and get the matching response
                    MemoryAddress singleResponse = OCSP_resp_get0(basicResponse, OCSP_resp_find(basicResponse, certId, -1));
                    return OCSP_single_get0_status(singleResponse, MemoryAddress.NULL,
                            MemoryAddress.NULL, MemoryAddress.NULL, MemoryAddress.NULL);
                }
            }
        } catch (Exception e) {
            logger.warn(sm.getString("engine.ocspRequestError", url.toString()), e);
        } finally {
            if (MemoryAddress.NULL.equals(ocspResponse)) {
                // Failed to get a valid response
                X509_STORE_CTX_set_error(x509ctx, X509_V_ERR_APPLICATION_VERIFICATION());
            }
            OCSP_CERTID_free(certId);
            OCSP_BASICRESP_free(basicResponse);
            OCSP_RESPONSE_free(ocspResponse);
            OCSP_REQUEST_free(ocspRequest);
            if (connection != null) {
                connection.disconnect();
            }
        }
        return V_OCSP_CERTSTATUS_UNKNOWN();
    }

    @Override
    public void setEnableSessionCreation(boolean b) {
        if (!b) {
            String msg = sm.getString("engine.noRestrictSessionCreation");
            throw new UnsupportedOperationException(msg);
        }
    }

    @Override
    public boolean getEnableSessionCreation() {
        return true;
    }


    private class OpenSSLSession implements SSLSession {

        // lazy init for memory reasons
        private Map<String, Object> values;

        // Last accessed time
        private long lastAccessedTime = -1;

        @Override
        public byte[] getId() {
            byte[] id = null;
            synchronized (OpenSSLEngine.this) {
                if (!destroyed) {
                    try (var scope = ResourceScope.newConfinedScope()) {
                        var allocator = SegmentAllocator.nativeAllocator(scope);
                        MemorySegment lenPointer = allocator.allocate(ValueLayout.ADDRESS);
                        var session = SSL_get_session(state.ssl);
                        MemoryAddress sessionId = SSL_SESSION_get_id(session, lenPointer);
                        int len = lenPointer.get(ValueLayout.JAVA_INT, 0);
                        id = (len == 0) ? new byte[0]
                                : MemorySegment.ofAddressNative(sessionId, len, scope).toArray(ValueLayout.JAVA_BYTE);
                    }
                }
            }

            return id;
        }

        @Override
        public SSLSessionContext getSessionContext() {
            return sessionContext;
        }

        @Override
        public long getCreationTime() {
            // We need to multiply by 1000 as OpenSSL uses seconds and we need milliseconds.
            long creationTime = 0;
            synchronized (OpenSSLEngine.this) {
                if (!destroyed) {
                    var session = SSL_get_session(state.ssl);
                    creationTime = SSL_SESSION_get_time(session);
                }
            }
            return creationTime * 1000L;
        }

        @Override
        public long getLastAccessedTime() {
            return (lastAccessedTime > 0) ? lastAccessedTime : getCreationTime();
        }

        @Override
        public void invalidate() {
            // NOOP
        }

        @Override
        public boolean isValid() {
            return false;
        }

        @Override
        public void putValue(String name, Object value) {
            if (name == null) {
                throw new IllegalArgumentException(sm.getString("engine.nullName"));
            }
            if (value == null) {
                throw new IllegalArgumentException(sm.getString("engine.nullValue"));
            }
            Map<String, Object> values = this.values;
            if (values == null) {
                // Use size of 2 to keep the memory overhead small
                values = this.values = new HashMap<>(2);
            }
            Object old = values.put(name, value);
            if (value instanceof SSLSessionBindingListener) {
                ((SSLSessionBindingListener) value).valueBound(new SSLSessionBindingEvent(this, name));
            }
            notifyUnbound(old, name);
        }

        @Override
        public Object getValue(String name) {
            if (name == null) {
                throw new IllegalArgumentException(sm.getString("engine.nullName"));
            }
            if (values == null) {
                return null;
            }
            return values.get(name);
        }

        @Override
        public void removeValue(String name) {
            if (name == null) {
                throw new IllegalArgumentException(sm.getString("engine.nullName"));
            }
            Map<String, Object> values = this.values;
            if (values == null) {
                return;
            }
            Object old = values.remove(name);
            notifyUnbound(old, name);
        }

        @Override
        public String[] getValueNames() {
            Map<String, Object> values = this.values;
            if (values == null || values.isEmpty()) {
                return new String[0];
            }
            return values.keySet().toArray(new String[0]);
        }

        private void notifyUnbound(Object value, String name) {
            if (value instanceof SSLSessionBindingListener) {
                ((SSLSessionBindingListener) value).valueUnbound(new SSLSessionBindingEvent(this, name));
            }
        }

        @Override
        public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
            // these are lazy created to reduce memory overhead
            Certificate[] c = peerCerts;
            if (c == null) {
                byte[] clientCert;
                byte[][] chain;
                synchronized (OpenSSLEngine.this) {
                    if (destroyed || SSL_in_init(state.ssl) != 0) {
                        throw new SSLPeerUnverifiedException(sm.getString("engine.unverifiedPeer"));
                    }
                    chain = getPeerCertChain();
                    if (!clientMode) {
                        // if used on the server side SSL_get_peer_cert_chain(...) will not include the remote peer certificate.
                        // We use SSL_get_peer_certificate to get it in this case and add it to our array later.
                        //
                        // See https://www.openssl.org/docs/ssl/SSL_get_peer_cert_chain.html
                        clientCert = getPeerCertificate();
                    } else {
                        clientCert = null;
                    }
                }
                if (chain == null && clientCert == null) {
                    return null;
                }
                int len = 0;
                if (chain != null) {
                    len += chain.length;
                }

                int i = 0;
                Certificate[] certificates;
                if (clientCert != null) {
                    len++;
                    certificates = new Certificate[len];
                    certificates[i++] = new OpenSSLX509Certificate(clientCert);
                } else {
                    certificates = new Certificate[len];
                }
                if (chain != null) {
                    int a = 0;
                    for (; i < certificates.length; i++) {
                        certificates[i] = new OpenSSLX509Certificate(chain[a++]);
                    }
                }
                c = peerCerts = certificates;
            }
            return c;
        }

        @Override
        public Certificate[] getLocalCertificates() {
            // FIXME (if possible): Not available in the OpenSSL API
            return EMPTY_CERTIFICATES;
        }

        @Deprecated
        @Override
        public javax.security.cert.X509Certificate[] getPeerCertificateChain()
                throws SSLPeerUnverifiedException {
            // these are lazy created to reduce memory overhead
            javax.security.cert.X509Certificate[] c = x509PeerCerts;
            if (c == null) {
                byte[][] chain;
                synchronized (OpenSSLEngine.this) {
                    if (destroyed || SSL_in_init(state.ssl) != 0) {
                        throw new SSLPeerUnverifiedException(sm.getString("engine.unverifiedPeer"));
                    }
                    chain = getPeerCertChain();
                }
                if (chain == null) {
                    throw new SSLPeerUnverifiedException(sm.getString("engine.unverifiedPeer"));
                }
                javax.security.cert.X509Certificate[] peerCerts =
                        new javax.security.cert.X509Certificate[chain.length];
                for (int i = 0; i < peerCerts.length; i++) {
                    try {
                        peerCerts[i] = javax.security.cert.X509Certificate.getInstance(chain[i]);
                    } catch (javax.security.cert.CertificateException e) {
                        throw new IllegalStateException(e);
                    }
                }
                c = x509PeerCerts = peerCerts;
            }
            return c;
        }

        @Override
        public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
            Certificate[] peer = getPeerCertificates();
            if (peer == null || peer.length == 0) {
                return null;
            }
            return principal(peer);
        }

        @Override
        public Principal getLocalPrincipal() {
            Certificate[] local = getLocalCertificates();
            if (local == null || local.length == 0) {
                return null;
            }
            return principal(local);
        }

        private Principal principal(Certificate[] certs) {
            return ((java.security.cert.X509Certificate) certs[0]).getIssuerX500Principal();
        }

        @Override
        public String getCipherSuite() {
            if (cipher == null) {
                String ciphers;
                synchronized (OpenSSLEngine.this) {
                    if (!handshakeFinished) {
                        return INVALID_CIPHER;
                    }
                    if (destroyed) {
                        return INVALID_CIPHER;
                    }
                    ciphers = SSL_CIPHER_get_name(SSL_get_current_cipher(state.ssl)).getUtf8String(0);
                }
                String c = OpenSSLCipherConfigurationParser.openSSLToJsse(ciphers);
                if (c != null) {
                    cipher = c;
                }
            }
            return cipher;
        }

        @Override
        public String getProtocol() {
            String applicationProtocol = OpenSSLEngine.this.applicationProtocol;
            if (applicationProtocol == null) {
                synchronized (OpenSSLEngine.this) {
                    if (!destroyed) {
                        applicationProtocol = getProtocolNegotiated();
                    }
                }
                if (applicationProtocol == null) {
                    applicationProtocol = fallbackApplicationProtocol;
                }
                if (applicationProtocol != null) {
                    OpenSSLEngine.this.applicationProtocol = applicationProtocol.replace(':', '_');
                } else {
                    OpenSSLEngine.this.applicationProtocol = applicationProtocol = "";
                }
            }
            String version = null;
            synchronized (OpenSSLEngine.this) {
                if (!destroyed) {
                    version = SSL_get_version(state.ssl).getUtf8String(0);
                }
            }
            if (applicationProtocol.isEmpty()) {
                return version;
            } else {
                return version + ':' + applicationProtocol;
            }
        }

        @Override
        public String getPeerHost() {
            // Not available for now in Tomcat (needs to be passed during engine creation)
            return null;
        }

        @Override
        public int getPeerPort() {
            // Not available for now in Tomcat (needs to be passed during engine creation)
            return 0;
        }

        @Override
        public int getPacketBufferSize() {
            return MAX_ENCRYPTED_PACKET_LENGTH;
        }

        @Override
        public int getApplicationBufferSize() {
            return MAX_PLAINTEXT_LENGTH;
        }

    }

    private static class OpenSSLState implements Runnable {

        private final ResourceScope scope;
        private final MemoryAddress ssl;
        private final MemoryAddress networkBIO;

        private OpenSSLState(ResourceScope scope, MemoryAddress ssl, MemoryAddress networkBIO) {
            this.scope = scope;
            this.ssl = ssl;
            this.networkBIO = networkBIO;
        }

        @Override
        public void run() {
            try {
                BIO_free(networkBIO);
                SSL_free(ssl);
            } finally {
                scope.close();
            }
        }
    }
}
