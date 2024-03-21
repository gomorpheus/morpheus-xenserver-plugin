package com.morpheusdata.xen.util

import org.apache.http.HttpException
import org.apache.http.HttpRequest
import org.apache.http.HttpRequestInterceptor
import org.apache.http.auth.AuthScope
import org.apache.http.client.protocol.ClientContext
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.protocol.HttpContext
import org.apache.http.protocol.HttpCoreContext

/**
 * @author rahul.ray
 */
class PreemptiveAuthInterceptor implements HttpRequestInterceptor {

    void process(final HttpRequest request, final HttpContext context) throws HttpException, IOException {
        def authState = context.getAttribute(ClientContext.TARGET_AUTH_STATE)
        // If no auth scheme avaialble yet, try to initialize it preemptively
        if(authState.getAuthScheme() == null) {
            def credsProvider = context.getAttribute(HttpClientContext.CREDS_PROVIDER)
            def targetHost = context.getAttribute(HttpCoreContext.HTTP_TARGET_HOST)
            def authScope = new AuthScope(targetHost.getHostName(), targetHost.getPort())
            def creds = credsProvider.getCredentials(authScope)
            authState.update(new BasicScheme(), creds)
        }
    }

}