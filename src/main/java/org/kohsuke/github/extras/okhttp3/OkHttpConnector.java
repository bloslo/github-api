package org.kohsuke.github.extras.okhttp3;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.CacheControl;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import org.kohsuke.github.HttpConnector;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link HttpConnector} for {@link OkHttpClient}.
 * <p>
 * Unlike {@link #DEFAULT}, OkHttp does response caching. Making a conditional request against GitHubAPI and receiving a
 * 304 response does not count against the rate limit. See http://developer.github.com/v3/#conditional-requests
 *
 * @author Liam Newman
 * @author Kohsuke Kawaguchi
 */
public class OkHttpConnector implements HttpConnector {
    private static final String HEADER_NAME = "Cache-Control";
    private final String maxAgeHeaderValue;

    private final OkHttpClient client;
    private final ObsoleteUrlFactory urlFactory;

    /**
     * Instantiates a new Ok http connector.
     *
     * @param client
     *            the client
     */
    public OkHttpConnector(OkHttpClient client) {
        this(client, 0);
    }

    /**
     * Instantiates a new Ok http connector.
     *
     * @param client
     *            the client
     * @param cacheMaxAge
     *            the cache max age
     */
    public OkHttpConnector(OkHttpClient client, int cacheMaxAge) {

        OkHttpClient.Builder builder = client.newBuilder();

        builder.connectionSpecs(TlsConnectionSpecs());

        if (cacheMaxAge >= 0 && client.cache() != null) {
            maxAgeHeaderValue = new CacheControl.Builder().maxAge(cacheMaxAge, TimeUnit.SECONDS).build().toString();
            // HttpURLConnection does not support networkInterceptors, so this would not work
            // However, we hacked ObsoleteUrlFactory to do this automatically for us.
            // builder.addNetworkInterceptor(new RemoveIfModifiedSinceRequestHeader());
        } else {
            maxAgeHeaderValue = null;
        }

        this.client = builder.build();

        this.urlFactory = new ObsoleteUrlFactory(this.client);
    }

    public HttpURLConnection connect(URL url) throws IOException {
        HttpURLConnection urlConnection = urlFactory.open(url);
        if (maxAgeHeaderValue != null) {
            // By default OkHttp honors max-age, meaning it will use local cache
            // without checking the network within that timeframe.
            // However, that can result in stale data being returned during that time so
            // we force network-based checking no matter how often the query is made.
            // OkHttp still automatically does ETag checking and returns cached data when
            // GitHub reports 304, but those do not count against rate limit.
            urlConnection.setRequestProperty(HEADER_NAME, maxAgeHeaderValue);
        }

        return urlConnection;
    }

    /** Returns connection spec with TLS v1.2 in it */
    private List<ConnectionSpec> TlsConnectionSpecs() {
        return Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT);
    }

    static class RemoveIfModifiedSinceRequestHeader implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request currentRequest = chain.request();
            if (currentRequest.header("If-Modified-Since") != null) {
                currentRequest = currentRequest.newBuilder()
                    .removeHeader("If-Modified-Since")
                    .build();
            }
            return chain.proceed(currentRequest);
        }
    }
}
