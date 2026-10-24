package com.controlresell.cli.release

import kotlin.test.*

class NetworkFailuresTest {
    private class CurlIllegalStateException(message: String) : IllegalStateException(message)
    private class DarwinHttpRequestException(message: String) : Exception(message)
    private class WinHttpException(message: String) : Exception(message)

    @Test fun theExceptionTypeDecidesBeforeAnyWording() {
        // The message names another family: the type wins.
        assertEquals(NetworkFailure.TLS, networkFailure(javax.net.ssl.SSLHandshakeException("Connection refused")))
        assertEquals(NetworkFailure.DNS, networkFailure(java.net.UnknownHostException("Connection timed out")))
        assertEquals(NetworkFailure.TIMEOUT, networkFailure(java.net.SocketTimeoutException("certificate unknown")))
        assertEquals(NetworkFailure.REFUSED, networkFailure(java.net.ConnectException("Name or service not known")))
        assertEquals(NetworkFailure.PROXY, networkFailure(java.net.ConnectException("Received HTTP code 407 from proxy after CONNECT")))
        assertEquals(NetworkFailure.CLOSED, networkFailure(io.ktor.utils.io.ClosedByteChannelException(null)))
    }

    @Test fun theTypeIsFoundThroughTheCauseChain() {
        assertEquals(NetworkFailure.DNS, networkFailure(IllegalStateException("wrapped", java.net.UnknownHostException("x"))))
        assertEquals(NetworkFailure.TLS, networkFailure(RuntimeException("outer", IllegalStateException("middle", javax.net.ssl.SSLException("x")))))
    }

    @Test fun aHostNameInAConnectionMessageIsNotATlsFailure() {
        assertEquals(NetworkFailure.REFUSED,
            networkFailure(CurlIllegalStateException("Connection failed for request. Reason: Failed to connect to proxy-SSL-gw port 3128")))
        assertEquals(NetworkFailure.DNS,
            networkFailure(CurlIllegalStateException("Connection failed for request. Reason: Could not resolve host: TLS-mirror.example")))
        assertEquals(NetworkFailure.TIMEOUT,
            networkFailure(CurlIllegalStateException("Connection failed for request. Reason: Connection timed out after 10000 ms to SSL.example")))
    }

    @Test fun theMostSpecificFamilyWinsWhenTheWordingMentionsSeveral() {
        fun curl(reason: String) = CurlIllegalStateException("Connection failed for request. Reason: $reason")
        assertEquals(NetworkFailure.TIMEOUT, networkFailure(curl("Failed to connect to api.github.com port 443 after 10001 ms: Timeout was reached")))
        assertEquals(NetworkFailure.TLS, networkFailure(curl("SSL connect error: connection timed out during the handshake")))
        assertEquals(NetworkFailure.TLS, networkFailure(curl("SSL certificate problem, Failed to connect afterwards")))
        assertEquals(NetworkFailure.TIMEOUT, networkFailure(DarwinHttpRequestException("The request timed out, via web proxy")))
    }

    @Test fun theWordingIsOnlyAFallbackPerFamily() {
        fun curl(reason: String) = CurlIllegalStateException("Connection failed for request. Reason: $reason")
        assertEquals(NetworkFailure.PROXY, networkFailure(curl("Could not resolve proxy: p.corp")))
        assertEquals(NetworkFailure.DNS, networkFailure(curl("Could not resolve host: api.github.com")))
        assertEquals(NetworkFailure.REFUSED, networkFailure(curl("Couldn't connect to server")))
        assertEquals(NetworkFailure.TIMEOUT, networkFailure(curl("Timeout was reached")))
        assertEquals(NetworkFailure.TLS, networkFailure(curl("SSL peer certificate or SSH remote key was not OK")))
        assertEquals(NetworkFailure.OTHER, networkFailure(curl("Send failure")))
        assertEquals(NetworkFailure.HEADERS, networkFailure(IllegalStateException("Header line length limit exceeded")))
    }

    @Test fun engineCodesCountOnlyForTheirOwnEngine() {
        assertEquals(NetworkFailure.DNS, networkFailure(DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1003 \"x\"")))
        assertEquals(NetworkFailure.TLS, networkFailure(DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1202 \"The certificate for this server is invalid, via web proxy\"")))
        assertEquals(NetworkFailure.DNS, networkFailure(WinHttpException("WinHttp error 12007")))
        // A port or number that happens to equal a code in another engine's message is not a failure family.
        assertEquals(NetworkFailure.OTHER, networkFailure(CurlIllegalStateException("Connection failed for request. Reason: Code=-1003 12007")))
    }

    @Test fun localFailuresAreNotNetworkFailures() {
        assertNull(networkFailure(kotlinx.io.IOException("No space left on device")))
        assertNull(networkFailure(UnsupportedOperationException("unexpected")))
    }
}
