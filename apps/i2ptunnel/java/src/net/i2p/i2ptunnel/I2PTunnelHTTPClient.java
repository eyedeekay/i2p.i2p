/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.Outproxy;
import net.i2p.client.I2PSession;
import net.i2p.client.LookupResult;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.crypto.Blinding;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.BlindData;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.i2ptunnel.localServer.LocalHTTPServer;
import net.i2p.i2ptunnel.util.HTTPRequestReader;
import net.i2p.util.ConvertToHash;
import net.i2p.util.DNSOverHTTPS;
import net.i2p.util.EventDispatcher;
import net.i2p.util.InternalSocket;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;

/**
 * Act as a mini HTTP proxy, handling various different types of requests,
 * forwarding them through I2P appropriately, and displaying the reply.  Supported
 * request formats are: <pre>
 *   $method http://$site[$port]/$path $protocolVersion
 * or
 *   $method $path $protocolVersion\nHost: $site
 * or
 *   $method http://i2p/$b64key/$path $protocolVersion
 * or
 *   $method /$site/$path $protocolVersion
 * or (deprecated)
 *   $method /eepproxy/$site/$path $protocolVersion
 * </pre>
 *
 * CONNECT (https) supported as of release 0.9.11.
 *
 * Note that http://i2p/$b64key/... and /eepproxy/$site/... are not recommended
 * in browsers or other user-visible applications, as relative links will not
 * resolve correctly, cookies won't work, etc.
 *
 * Note that http://$b64key/... and http://$b64key.i2p/... are NOT supported, as
 * a b64 key may contain '=' and '~', both of which are illegal hostname characters.
 * Rewrite as http://i2p/$b64key/...
 *
 * If the $site resolves with the I2P naming service, then it is directed towards
 * that I2P Site, otherwise it is directed towards this client's outproxy (typically
 * "squid.i2p").  Only HTTP and HTTPS are supported (no ftp, mailto, etc).  Both GET
 * and POST have been tested, though other $methods should work.
 *
 */
public class I2PTunnelHTTPClient extends I2PTunnelHTTPClientBase implements Runnable {

    /**
     *  Map of hostname to base64 destination for destinations collected
     *  via address helper links
     */
    private final ConcurrentHashMap<String, String> addressHelpers = new ConcurrentHashMap<String, String>(8);

    /**
     *  Used to protect actions via http://proxy.i2p/
     */
    private final String _proxyNonce;

    public static final String AUTH_REALM = "I2P HTTP Proxy";
    private static final String UA_I2P = "User-Agent: " +
                                         "MYOB/6.66 (AN/ON)" +
                                         "\r\n";
    // ESR version of Firefox, same as Tor Browser
    private static final String UA_CLEARNET = "User-Agent: " +
                                              DNSOverHTTPS.UA_CLEARNET +
                                              "\r\n";
    // overrides
    private static final String PROP_UA_I2P = "httpclient.userAgent.i2p";
    private static final String PROP_UA_CLEARNET = "httpclient.userAgent.outproxy";
    public static final String OPT_KEEPALIVE_BROWSER = "keepalive.browser";
    public static final String OPT_KEEPALIVE_I2P = "keepalive.i2p";

    // how long to wait for another request on the same socket
    // Firefox timeout appears to be about 114 seconds, so it will close before we do.
    static final int BROWSER_KEEPALIVE_TIMEOUT = 2*60*1000;
    private static final boolean DEFAULT_KEEPALIVE_BROWSER = true;
    private static final boolean DEFAULT_KEEPALIVE_I2P = true;

    /**
     *  These are backups if the xxx.ht error page is missing.
     */
    private final static String ERR_REQUEST_DENIED =
            "HTTP/1.1 403 Access Denied\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: REQUEST DENIED</H1>" +
            "You attempted to connect to a non-I2P website or location.<BR>";

    /*****
    private final static byte[] ERR_TIMEOUT =
    ("HTTP/1.1 504 Gateway Timeout\r\n"+
    "Content-Type: text/html; charset=iso-8859-1\r\n"+
    "Cache-Control: no-cache\r\n\r\n"+
    "<html><body><H1>I2P ERROR: TIMEOUT</H1>"+
    "That Destination was reachable, but timed out getting a "+
    "response.  This is likely a temporary error, so you should simply "+
    "try to refresh, though if the problem persists, the remote "+
    "destination may have issues.  Could not get a response from "+
    "the following Destination:<BR><BR>")
    .getBytes();
     *****/
    private final static String ERR_NO_OUTPROXY =
            "HTTP/1.1 503 Service Unavailable\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: No outproxy found</H1>" +
            "Your request was for a site outside of I2P, but you have no " +
            "HTTP outproxy configured.  Please configure an outproxy in I2PTunnel";

    private final static String ERR_AHELPER_CONFLICT =
            "HTTP/1.1 409 Conflict\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: Destination key conflict</H1>" +
            "The addresshelper link you followed specifies a different destination key " +
            "than a host entry in your host database. " +
            "Someone could be trying to impersonate another website, " +
            "or people have given two websites identical names.<p>" +
            "You can resolve the conflict by considering which key you trust, " +
            "and either discarding the addresshelper link, " +
            "discarding the host entry from your host database, " +
            "or naming one of them differently.<p>";

    private final static String ERR_AHELPER_NOTFOUND =
            "HTTP/1.1 404 Not Found\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: Helper key not resolvable.</H1>" +
            "The helper key you put for i2paddresshelper= is not resolvable. " +
            "It seems to be garbage data, or a mistyped b32. Check your URL " +
            "to try and fix the helper key to be either a b32 or a base64.";

    private final static String ERR_AHELPER_NEW =
            "HTTP/1.1 409 New Address\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>New Host Name with Address Helper</H1>" +
            "The address helper link you followed is for a new hostname that is not in your address book. " +
            "You may either save the destination for this hostname to your address book, or remember it only until your router restarts. " +
            "If you save it to your address book, you will not see this message again. " +
            "If you do not wish to visit this host, click the \"back\" button on your browser.";

    private final static String ERR_BAD_PROTOCOL =
            "HTTP/1.1 403 Bad Protocol\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: NON-HTTP PROTOCOL</H1>" +
            "The request uses a bad protocol. " +
            "The I2P HTTP Proxy supports HTTP and HTTPS requests only. Other protocols such as FTP are not allowed.<BR>";

    private final static String ERR_BAD_URI =
            "HTTP/1.1 403 Bad URI\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: INVALID REQUEST URI</H1>" +
            "The request URI is invalid, and probably contains illegal characters. " +
            "If you clicked e.g. a forum link, check the end of the URI for any characters the browser has mistakenly added on.<BR>";

    private final static String ERR_LOCALHOST =
            "HTTP/1.1 403 Access Denied\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: REQUEST DENIED</H1>" +
            "Your browser is misconfigured. Do not use the proxy to access the router console or other localhost destinations.<BR>";

    private final static String ERR_INTERNAL_SSL =
            "HTTP/1.1 403 SSL Rejected\r\n" +
            "Content-Type: text/html; charset=iso-8859-1\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "<html><body><H1>I2P ERROR: SSL to I2P address rejected</H1>" +
            "SSL to .i2p addresses denied by configuration." +
            "You may change the configuration in I2PTunnel";

    /**
     *  This constructor always starts the tunnel (ignoring the i2cp.delayOpen option).
     *  It is used to add a client to an existing socket manager.
     *
     *  As of 0.9.20 this is fast, and does NOT connect the manager to the router,
     *  or open the local socket. You MUST call startRunning() for that.
     *
     *  @param sockMgr the existing socket manager
     */
    public I2PTunnelHTTPClient(int localPort, Logging l, I2PSocketManager sockMgr, I2PTunnel tunnel, EventDispatcher notifyThis, long clientId) {
        super(localPort, l, sockMgr, tunnel, notifyThis, clientId);
        _proxyNonce = Long.toString(_context.random().nextLong());
        // proxyList = new ArrayList();

        setName("HTTP Proxy on " + getTunnel().listenHost + ':' + localPort);
        notifyEvent("openHTTPClientResult", "ok");
    }

    /**
     *  As of 0.9.20 this is fast, and does NOT connect the manager to the router,
     *  or open the local socket. You MUST call startRunning() for that.
     *
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelHTTPClient(int localPort, Logging l, boolean ownDest,
                               String wwwProxy, EventDispatcher notifyThis,
                               I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, ownDest, l, notifyThis, "HTTP Proxy on " + tunnel.listenHost + ':' + localPort, tunnel);
        _proxyNonce = Long.toString(_context.random().nextLong());

        //proxyList = new ArrayList(); // We won't use outside of i2p

        if(wwwProxy != null) {
            StringTokenizer tok = new StringTokenizer(wwwProxy, ", ");
            while(tok.hasMoreTokens()) {
                _proxyList.add(tok.nextToken().trim());
            }
        }

        setName("HTTP Proxy on " + tunnel.listenHost + ':' + localPort);
        notifyEvent("openHTTPClientResult", "ok");
    }

    /**
     * Create the default options (using the default timeout, etc).
     * Warning, this does not make a copy of I2PTunnel's client options,
     * it modifies them directly.
     * unused?
     *
     * This will throw IAE on tunnel build failure
     */
    @Override
    protected I2PSocketOptions getDefaultOptions() {
        Properties defaultOpts = getTunnel().getClientOptions();
        if(!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT)) {
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, "" + DEFAULT_READ_TIMEOUT);
        }
        //if (!defaultOpts.contains("i2p.streaming.inactivityTimeout"))
        //    defaultOpts.setProperty("i2p.streaming.inactivityTimeout", ""+DEFAULT_READ_TIMEOUT);
        // delayed start
        verifySocketManager();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if(!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT)) {
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        }
        return opts;
    }

    /**
     * Create the default options (using the default timeout, etc).
     * Warning, this does not make a copy of I2PTunnel's client options,
     * it modifies them directly.
     * Do not use overrides for per-socket options.
     *
     * This will throw IAE on tunnel build failure
     */
    @Override
    protected I2PSocketOptions getDefaultOptions(Properties overrides) {
        Properties defaultOpts = getTunnel().getClientOptions();
        defaultOpts.putAll(overrides);
        if(!defaultOpts.contains(I2PSocketOptions.PROP_READ_TIMEOUT)) {
            defaultOpts.setProperty(I2PSocketOptions.PROP_READ_TIMEOUT, "" + DEFAULT_READ_TIMEOUT);
        }
        if(!defaultOpts.contains("i2p.streaming.inactivityTimeout")) {
            defaultOpts.setProperty("i2p.streaming.inactivityTimeout", "" + DEFAULT_READ_TIMEOUT);
        }
        // delayed start
        verifySocketManager();
        I2PSocketOptions opts = sockMgr.buildOptions(defaultOpts);
        if(!defaultOpts.containsKey(I2PSocketOptions.PROP_CONNECT_TIMEOUT)) {
            opts.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
        }
        return opts;
    }
    private InternalSocketRunner isr;

    /**
     * Actually start working on incoming connections.
     * Overridden to start an internal socket too.
     *
     */
    @Override
    public void startRunning() {
        // following are for HTTPResponseOutputStream
        //_context.statManager().createRateStat("i2ptunnel.httpCompressionRatio", "ratio of compressed size to decompressed size after transfer", "I2PTunnel", new long[] { 60*60*1000 });
        //_context.statManager().createRateStat("i2ptunnel.httpCompressed", "compressed size transferred", "I2PTunnel", new long[] { 60*60*1000 });
        //_context.statManager().createRateStat("i2ptunnel.httpExpanded", "size transferred after expansion", "I2PTunnel", new long[] { 60*60*1000 });
        super.startRunning();
        if (open) {
            this.isr = new InternalSocketRunner(this);
            this.isr.start();
            int port = getLocalPort();
            _context.portMapper().register(PortMapper.SVC_HTTP_PROXY, getTunnel().listenHost, port);
            _context.portMapper().register(PortMapper.SVC_HTTPS_PROXY, getTunnel().listenHost, port);
        }
    }

    /**
     * Overridden to close internal socket too.
     */
    @Override
    public boolean close(boolean forced) {
        int port = getLocalPort();
        int reg = _context.portMapper().getPort(PortMapper.SVC_HTTP_PROXY);
        if (reg == port) {
            _context.portMapper().unregister(PortMapper.SVC_HTTP_PROXY);
        }
        reg = _context.portMapper().getPort(PortMapper.SVC_HTTPS_PROXY);
        if (reg == port) {
            _context.portMapper().unregister(PortMapper.SVC_HTTPS_PROXY);
        }
        boolean rv = super.close(forced);
        if(this.isr != null) {
            this.isr.stopRunning();
        }
        return rv;
    }

    /** @since 0.9.4 */
    protected String getRealm() {
        return AUTH_REALM;
    }

    private static final String HELPER_PARAM = "i2paddresshelper";
    public static final String LOCAL_SERVER = "proxy.i2p";
    private static final boolean DEFAULT_GZIP = true;
    /** all default to false */
    public static final String PROP_REFERER = "i2ptunnel.httpclient.sendReferer";
    public static final String PROP_USER_AGENT = "i2ptunnel.httpclient.sendUserAgent";
    public static final String PROP_VIA = "i2ptunnel.httpclient.sendVia";
    public static final String PROP_JUMP_SERVERS = "i2ptunnel.httpclient.jumpServers";
    public static final String PROP_DISABLE_HELPER = "i2ptunnel.httpclient.disableAddressHelper";
    /** @since 0.9.14 */
    public static final String PROP_ACCEPT = "i2ptunnel.httpclient.sendAccept";
    /** @since 0.9.14, overridden to true as of 0.9.35 unlesss PROP_SSL_SET is set */
    public static final String PROP_INTERNAL_SSL = "i2ptunnel.httpclient.allowInternalSSL";
    /** @since 0.9.35 */
    public static final String PROP_SSL_SET = "sslManuallySet";

    /**
     *
     *  Note: This does not handle RFC 2616 header line splitting,
     *  which is obsoleted in RFC 7230.
     */
    protected void clientConnectionRun(Socket s) {
        OutputStream out = null;

        /**
         * The URL after fixup, always starting with http:// or https://
         */
        String targetRequest = null;

        // in-net outproxy
        boolean usingWWWProxy = false;
        // local outproxy plugin
        boolean usingInternalOutproxy = false;
        Outproxy outproxy = null;
        boolean usingInternalServer = false;
        String internalPath = null;
        String internalRawQuery = null;
        String currentProxy = null;
        long requestId = __requestId.incrementAndGet();
        boolean shout = false;
        boolean isConnect = false;
        boolean isHead = false;
        I2PSocket i2ps = null;
        try {
            s.setSoTimeout(INITIAL_SO_TIMEOUT);
            out = s.getOutputStream();
            InputReader reader = new InputReader(s.getInputStream());
            int requestCount = 0;
            // HTTP Persistent Connections (RFC 2616)
            // for the local browser-to-client-proxy socket.
            // Keep it very simple.
            // Will be set to false for non-GET/HEAD, non-HTTP/1.1,
            // Connection: close, InternalSocket,
            // or after analysis of the response headers in HTTPResponseOutputStream,
            // or on errors in I2PTunnelRunner.
            boolean keepalive = getBooleanOption(OPT_KEEPALIVE_BROWSER, DEFAULT_KEEPALIVE_BROWSER) &&
                                !(s instanceof InternalSocket);

          // indent
          do {   // while (keepalive)
          // indent

            if (requestCount > 0) {
                try {
                    s.setSoTimeout(BROWSER_KEEPALIVE_TIMEOUT);
                } catch (IOException ioe) {
                    if (_log.shouldInfo())
                        _log.info("Socket closed before request #" + requestCount);
                    return;
                }
                if (_log.shouldInfo())
                    _log.info("Keepalive, awaiting request #" + requestCount);
            }
            final HTTPRequestReader hrr = new HTTPRequestReader(s, _context, reader, __requestId,
            I2PTunnelHTTPClientBase.BROWSER_READ_TIMEOUT, getTunnel(), this);
            //String line, method = null, protocol = null, host = null, destination = null;
            String method = hrr.getMethod();
            String protocol = hrr.getProtocol();
            String hostLowerCase = hrr.getHostLowerCase();
            StringBuilder newRequest = new StringBuilder();
            boolean ahelperPresent = hrr.getAhelperNew();
            boolean ahelperNew = hrr.getAhelperNew();
            String ahelperKey = hrr.getAhelperKey();
            String userAgent = hrr.getUserAgent();
            String authorization = hrr.getAuthorization();
            int remotePort = hrr.getRemotePort();
            String referer = hrr.getReferer();
            URI origRequestURI = null;
            boolean preserveConnectionHeader = false;
            boolean allowGzip = hrr.getAllowGzip();
            String destination = hrr.getDestination();
            String host = hrr.getHost();

            if (newRequest.length() > 0 && _log.shouldDebug())
                _log.debug(getPrefix(requestId) + "NewRequest header: [" + newRequest + ']');

            if(method == null || (destination == null && !usingInternalOutproxy)) {
                if (requestCount > 0) {
                    // SocketTimeout, normal to get here for persistent connections,
                    // because DataHelper.readLine() returns null on EOF
                    return;
                }
                _log.debug("No HTTP method found in the request.");
                try {
                    if (protocol != null && "http".equals(protocol.toLowerCase(Locale.US))) {
                        out.write(getErrorPage("denied", ERR_REQUEST_DENIED).getBytes("UTF-8"));
                    } else {
                        out.write(getErrorPage("protocol", ERR_BAD_PROTOCOL).getBytes("UTF-8"));
                    }
                    writeFooter(out);
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            if(_log.shouldLog(Log.DEBUG)) {
                _log.debug(getPrefix(requestId) + "Destination: " + destination);
            }

            // Authorization
            // Yes, this is sent and checked for every request on a persistent connection
            AuthResult result = authorize(s, requestId, method, authorization);
            if (result != AuthResult.AUTH_GOOD) {
                if(_log.shouldLog(Log.WARN)) {
                    if(authorization != null) {
                        _log.warn(getPrefix(requestId) + "Auth failed, sending 407 again");
                    } else {
                        _log.warn(getPrefix(requestId) + "Auth required, sending 407");
                    }
                }
                try {
                    out.write(getAuthError(result == AuthResult.AUTH_STALE).getBytes("UTF-8"));
                    writeFooter(out);
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            // Serve local proxy files (images, css linked from error pages)
            // Ignore all the headers
            if (usingInternalServer) {
                try {
                    // disable the add form if address helper is disabled
                    if(internalPath.equals("/add") &&
                            Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER))) {
                        out.write(ERR_HELPER_DISABLED.getBytes("UTF-8"));
                    } else {
                        LocalHTTPServer.serveLocalFile(_context, sockMgr, out, method, internalPath, internalRawQuery, _proxyNonce, allowGzip);
                    }
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            // no destination, going to outproxy plugin
            if (usingInternalOutproxy) {
                Socket outSocket = outproxy.connect(host, remotePort);
                OnTimeout onTimeout = new OnTimeout(s, s.getOutputStream(), targetRequest, usingWWWProxy, currentProxy, requestId);
                byte[] data;
                byte[] response;
                if (isConnect) {
                    data = null;
                    response = SUCCESS_RESPONSE.getBytes("UTF-8");
                } else {
                    data = newRequest.toString().getBytes("ISO-8859-1");
                    response = null;
                }
                Thread t = new I2PTunnelOutproxyRunner(s, outSocket, sockLock, data, response, onTimeout);
                // we are called from an unlimited thread pool, so run inline
                //t.start();
                t.run();
                return;
            }

            // LOOKUP
            // If the host is "i2p", the getHostName() lookup failed, don't try to
            // look it up again as the naming service does not do negative caching
            // so it will be slow.
            Destination clientDest = null;
            String addressHelper = addressHelpers.get(destination.toLowerCase(Locale.US));
            if(addressHelper != null) {
                clientDest = _context.namingService().lookup(addressHelper);
                if(clientDest == null) {
                    // remove bad entries
                    addressHelpers.remove(destination.toLowerCase(Locale.US));
                    if(_log.shouldLog(Log.WARN)) {
                        _log.warn(getPrefix(requestId) + "Could not find destination for " + addressHelper);
                    }
                    String header = getErrorPage("ahelper-notfound", ERR_AHELPER_NOTFOUND);
                    try {
                        writeErrorMessage(header, out, targetRequest, false, destination);
                    } catch (IOException ioe) {
                        // ignore
                    }
                    return;
                }
            } else if("i2p".equals(host)) {
                clientDest = null;
            } else if (destination.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
                int len = destination.length();
                if (len < 60 || (len >= 61 && len <= 63)) {
                    // 8-59 or 61-63 chars, this won't work
                    String header = getErrorPage("b32", ERR_DESTINATION_UNKNOWN);
                    try {
                        writeErrorMessage(header, _t("Corrupt Base32 address"), out, targetRequest, false, destination);
                    } catch (IOException ioe) {}
                    return;
                }
                if (len >= 64) {
                    // catch b33 errors before session lookup
                    try {
                        BlindData bd = Blinding.decode(_context, destination);
                        if (_log.shouldWarn())
                            _log.warn("Resolved b33 " + bd);
                        // TESTING
                        //sess.sendBlindingInfo(bd, 24*60*60*1000);
                    } catch (IllegalArgumentException iae) {
                        if (_log.shouldWarn())
                            _log.warn("Unable to resolve b33 " + destination, iae);
                        // b33 error page
                        String header = getErrorPage("b32", ERR_DESTINATION_UNKNOWN);
                        try {
                            writeErrorMessage(header, iae.getMessage(), out, targetRequest, false, destination);
                        } catch (IOException ioe) {}
                        return;
                    }
                }
                // use existing session to look up for efficiency
                verifySocketManager();
                I2PSession sess = sockMgr.getSession();
                if (!sess.isClosed()) {
                    if (len == 60) {
                        byte[] hData = Base32.decode(destination.substring(0, 52));
                        if (hData != null) {
                            if (_log.shouldInfo())
                                _log.info("lookup b32 in-session " + destination);
                            Hash hash = Hash.create(hData);
                            clientDest = sess.lookupDest(hash, 20*1000);
                        } else {
                            clientDest = null;
                        }
                    } else if (len >= 64) {
                        if (_log.shouldInfo())
                            _log.info("lookup b33 in-session " + destination);
                        LookupResult lresult = sess.lookupDest2(destination, 20*1000);
                        clientDest = lresult.getDestination();
                        int code = lresult.getResultCode();
                        if (code != LookupResult.RESULT_SUCCESS) {
                            if (_log.shouldWarn())
                                _log.warn("Unable to resolve b33 " + destination + " error code " + code);
                            if (code != LookupResult.RESULT_FAILURE) {
                                // form to supply missing data
                                writeB32SaveForm(out, destination, code, targetRequest);
                                return;
                            }
                            // fall through to standard destination unreachable error page
                        }
                    }
                } else {
                    if (_log.shouldInfo())
                        _log.info("lookup b32 out of session " + destination);
                    // TODO can't get result code from here
                    clientDest = _context.namingService().lookup(destination);
                }
            } else {
                if (_log.shouldInfo())
                    _log.info("lookup hostname " + destination);
                clientDest = _context.namingService().lookup(destination);
            }

            if(clientDest == null) {
                //l.log("Could not resolve " + destination + ".");
                if(_log.shouldLog(Log.WARN)) {
                    _log.warn("Unable to resolve " + destination + " (proxy? " + usingWWWProxy + ", request: " + targetRequest);
                }
                String header;
                String jumpServers = null;
                String extraMessage = null;
                if(usingWWWProxy) {
                    header = getErrorPage("dnfp", ERR_DESTINATION_UNKNOWN);
                } else if(ahelperPresent) {
                    header = getErrorPage("dnfb", ERR_DESTINATION_UNKNOWN);
                } else if(destination.length() >= 60 && destination.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
                    header = getErrorPage("nols", ERR_DESTINATION_UNKNOWN);
                    extraMessage = _t("Destination lease set not found");
                } else {
                    header = getErrorPage("dnfh", ERR_DESTINATION_UNKNOWN);
                    jumpServers = getTunnel().getClientOptions().getProperty(PROP_JUMP_SERVERS);
                    if(jumpServers == null) {
                        jumpServers = DEFAULT_JUMP_SERVERS;
                    }
                    int jumpDelay = 400 + _context.random().nextInt(256);
                    try {
                        Thread.sleep(jumpDelay);
                    } catch (InterruptedException ie) {}
                }
                try {
                    writeErrorMessage(header, extraMessage, out, targetRequest, usingWWWProxy, destination, jumpServers);
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            // as of 0.9.35, allowInternalSSL defaults to true, and overridden to true unless PROP_SSL_SET is set
            if (isConnect &&
                !usingWWWProxy &&
                getTunnel().getClientOptions().getProperty(PROP_SSL_SET) != null &&
                !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_INTERNAL_SSL, "true"))) {
                try {
                    writeErrorMessage(ERR_INTERNAL_SSL, out, targetRequest, false, destination);
                } catch (IOException ioe) {
                    // ignore
                }
                if (_log.shouldLog(Log.WARN))
                    _log.warn("SSL to i2p destinations denied by configuration: " + targetRequest);
                return;
            }

            // Address helper response form
            // This will only load once - the second time it won't be "new"
            // Don't do this for eepget, which uses a user-agent of "Wget"
            if(ahelperNew && "GET".equals(method) &&
                    (userAgent == null || !userAgent.startsWith("Wget")) &&
                    !Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_DISABLE_HELPER))) {
                try {
                    writeHelperSaveForm(out, destination, ahelperKey, targetRequest, referer);
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            // Redirect to non-addresshelper URL to not clog the browser address bar
            // and not pass the parameter to the I2P Site.
            // This also prevents the not-found error page from looking bad
            // Syndie can't handle a redirect of a POST
            if (ahelperPresent && !"POST".equals(method) && !"PUT".equals(method)) {
                String uri = targetRequest;
                if(_log.shouldLog(Log.DEBUG)) {
                    _log.debug("Auto redirecting to " + uri);
                }
                try {
                    out.write(("HTTP/1.1 301 Address Helper Accepted\r\n" +
                        "Location: " + uri + "\r\n" +
                        "Connection: close\r\n"+
                        "\r\n").getBytes("UTF-8"));
                } catch (IOException ioe) {
                    // ignore
                }
                return;
            }

            // Close persistent I2PSocket if destination or port changes
            // and open a new one.
            // We do not maintain a pool of open I2PSockets or look for
            // an available one. Keep it very simple.
            // As long as the traffic keeps going to the same place
            // we will keep reusing it.
            // While we should be able to reuse it if only the port changes,
            // that should be extremely rare, so don't bother.
            // For common use patterns including outproxy use,
            // this should still be quite effective.
            if (i2ps == null || i2ps.isClosed() ||
                remotePort != i2ps.getPort() ||
                !clientDest.equals(i2ps.getPeerDestination())) {
                if (i2ps != null) {
                    if (_log.shouldInfo())
                        _log.info("Old socket closed or different dest/port, opening new one");
                    try { i2ps.close(); } catch (IOException ioe) {}
                }
                Properties opts = new Properties();
                //opts.setProperty("i2p.streaming.inactivityTimeout", ""+120*1000);
                // 1 == disconnect.  see ConnectionOptions in the new streaming lib, which i
                // dont want to hard link to here
                //opts.setProperty("i2p.streaming.inactivityTimeoutAction", ""+1);
                I2PSocketOptions sktOpts;
                try {
                    sktOpts = getDefaultOptions(opts);
                } catch (RuntimeException re) {
                    // tunnel build failure
                    StringBuilder buf = new StringBuilder(128);
                    buf.append("HTTP/1.1 503 Service Unavailable");
                    if (re.getMessage() != null)
                        buf.append(" - ").append(re.getMessage());
                    buf.append("\r\n\r\n");
                    try {
                        out.write(buf.toString().getBytes("UTF-8"));
                    } catch (IOException ioe) {}
                    throw re;
                }
                if (remotePort > 0)
                    sktOpts.setPort(remotePort);
                i2ps = createI2PSocket(clientDest, sktOpts);
            }

            I2PTunnelRunner t;
            I2PTunnelHTTPClientRunner hrunner = null;
            if (isConnect) {
                byte[] data;
                byte[] response;
                if (usingWWWProxy) {
                    data = newRequest.toString().getBytes("ISO-8859-1");
                    response = null;
                } else {
                    data = null;
                    response = SUCCESS_RESPONSE.getBytes("UTF-8");
                }
                // no OnTimeout, we can't send HTTP error responses after sending SUCCESS_RESPONSE.
                t = new I2PTunnelRunner(s, i2ps, sockLock, data, response, mySockets, (OnTimeout) null);
            } else {
                byte[] data = newRequest.toString().getBytes("ISO-8859-1");
                OnTimeout onTimeout = new OnTimeout(s, s.getOutputStream(), targetRequest, usingWWWProxy,
                                                    currentProxy, requestId, hostLowerCase, isConnect);
                boolean keepaliveI2P = keepalive && getBooleanOption(OPT_KEEPALIVE_I2P, DEFAULT_KEEPALIVE_I2P);
                hrunner = new I2PTunnelHTTPClientRunner(s, i2ps, sockLock, data, mySockets, onTimeout,
                                                        keepaliveI2P, keepalive, isHead);
                t = hrunner;
            }
            if (usingWWWProxy) {
                t.setSuccessCallback(new OnProxySuccess(currentProxy, hostLowerCase, isConnect));
            }
            // we are called from an unlimited thread pool, so run inline
            //t.start();
            t.run();

            // I2PTunnelHTTPClientRunner spins off the browser-to-i2p thread and keeps
            // the i2p-to-socket copier in-line. So we won't get here until the i2p socket is closed.
            // check if whatever was in the response does not allow keepalive
            if (keepalive && hrunner != null && !hrunner.getKeepAliveSocket())
                break;
            // The old I2P socket was closed, null it out so we'll get a new one
            // next time around
            if (hrunner != null && !hrunner.getKeepAliveI2P())
                i2ps = null;
            // go around again
            requestCount++;

          // indent
          } while (keepalive);
          // indent

        } catch(IOException ex) {
            // This is normal for keepalive when the browser closed the socket,
            // or a SocketTimeoutException if we gave up first
            if(_log.shouldLog(Log.INFO)) {
                _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            }
            handleClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
        } catch(I2PException ex) {
            if(_log.shouldLog(Log.INFO)) {
                _log.info(getPrefix(requestId) + "Error trying to connect", ex);
            }
            handleClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
        } catch(OutOfMemoryError oom) {
            IOException ex = new IOException("OOM");
            _log.error(getPrefix(requestId) + "Error trying to connect", oom);
            handleClientException(ex, out, targetRequest, usingWWWProxy, currentProxy, requestId);
        } finally {
            // only because we are running it inline
            closeSocket(s);
            if (i2ps != null) try { i2ps.close(); } catch (IOException ioe) {}
        }
    }

    /**
     * @param destination the hostname
     * @since 0.8.7
     */
    private void writeHelperSaveForm(OutputStream outs, String destination, String ahelperKey,
                                     String targetRequest, String referer) throws IOException {
        if(outs == null)
            return;
        String idn = decodeIDNHost(destination);
        Writer out = new BufferedWriter(new OutputStreamWriter(outs, "UTF-8"));
        String header = getErrorPage("ahelper-new", ERR_AHELPER_NEW);
        out.write(header);
        out.write("<table id=\"proxyNewHost\">\n<tr><td align=\"right\">" + _t("Host") +
                "</td><td>" + idn + "</td></tr>\n");
        try {
            String b32 = Base32.encode(SHA256Generator.getInstance().calculateHash(Base64.decode(ahelperKey)).getData());
            out.write("<tr><td align=\"right\">" + _t("Base32") + "</td>" +
                    "<td><a href=\"http://" + b32 + ".b32.i2p/\">" + b32 + ".b32.i2p</a></td></tr>");
        } catch(Exception e) {
        }
        out.write("<tr><td align=\"right\">" + _t("Destination") + "</td><td>" +
                  "<textarea rows=\"1\" style=\"height: 6em; min-width: 0; min-height: 0;\" cols=\"70\" wrap=\"off\" readonly=\"readonly\" >" + ahelperKey + "</textarea>" +
                  "</td></tr>\n</table>\n" + "<hr>\n" +

                // FIXME if there is a query remaining it is lost
                "<form method=\"GET\" action=\"" + targetRequest + "\" class=\"hostaddform\">\n" +
                "<div class=\"formaction hostaddaction\"><button type=\"submit\" class=\"go hostadd\">" +
                "<span class=\"unicodeicon\">&#10143;</span><h4>" + _t("Continue to {0} without saving", idn) + "</h4>\n<p>" +
                _t("You can browse to the site without saving it to the address book. The address will be remembered until you restart your I2P router.") +
                "</p>\n</button></div>" + "\n</form>\n" +

                "<form method=\"GET\" action=\"http://" + LOCAL_SERVER + "/add\" class=\"hostaddform\">\n" +
                "<input type=\"hidden\" name=\"host\" value=\"" + destination + "\">\n" +
                "<input type=\"hidden\" name=\"dest\" value=\"" + ahelperKey + "\">\n" +
                "<input type=\"hidden\" name=\"nonce\" value=\"" + _proxyNonce + "\">\n");

        // FIXME wasn't escaped
        String label = _t("Save & continue").replace("&", "&amp;");
        out.write("<div class=\"formaction hostaddaction\"><button type=\"submit\" class=\"accept hostadd\" name=\"router\" value=\"router\">" +
                "<span class=\"unicodeicon\">&#10143;</span><h4>" + _t("Save {0} to router address book and continue to website", idn) + "</h4>\n<p>" +
                _t("This address will be saved to your Router address book where your subscription-based addresses are stored."));
                if(_context.namingService().getName().equals("BlockfileNamingService")) {
                    out.write(" " + _t("If you want to keep track of sites you have added manually, add to your Local or Private address book instead."));
                }
                out.write("</p>\n</button></div>\n");

        if(_context.namingService().getName().equals("BlockfileNamingService")) {
            // only blockfile supports multiple books

            out.write("<div class=\"formaction hostaddaction\"><button type=\"submit\" class=\"accept hostadd\" name=\"local\" value=\"local\">" +
            "<span class=\"unicodeicon\">&#10143;</span><h4>" + _t("Save {0} to local address book and continue to website", idn) + "</h4>\n<p>" +
            _t("This address will be saved to your Local address book. Select this option for addresses you wish to keep separate from the main router address book, but don't mind publishing.") +
            "</p>\n</button></div>\n");

            out.write("<div class=\"formaction hostaddaction\"><button type=\"submit\" class=\"accept hostadd\" name=\"private\" value=\"private\">" +
            "<span class=\"unicodeicon\">&#10143;</span><h4>" + _t("Save {0} to private address book and continue to website", idn) + "</h4>\n<p>" +
            _t("This address will be saved to your Private address book, ensuring it is never published.") +
             "</p>\n</button></div>\n");

        }
        // Firefox (and others?) don't send referer to meta refresh target, which is
        // what the jump servers use, so this isn't that useful.
        if (referer != null)
            out.write("<input type=\"hidden\" name=\"referer\" value=\"" + referer + "\">\n");
        out.write("<input type=\"hidden\" name=\"url\" value=\"" + targetRequest + "\">\n" +
                "</form>\n</div>\n");
        writeFooter(out);
    }

    /** @since 0.9.43 */
    private void writeB32SaveForm(OutputStream outs, String destination, int code,
                                     String targetRequest) throws IOException {
        if(outs == null)
            return;
        Writer out = new BufferedWriter(new OutputStreamWriter(outs, "UTF-8"));
        String header = getErrorPage("b32-auth", ERR_DESTINATION_UNKNOWN);
        out.write(header);
        out.write("<table id=\"proxyNewHost\">\n" +
                  "<tr><td align=\"right\">" + _t("Base32") + "</td>" +
                  "<td>" + destination + "</td></tr>" +
                  "\n</table>\n" + "<hr>");
        String msg;
        if (code == LookupResult.RESULT_SECRET_REQUIRED)
            msg = _t("Base32 address requires lookup password");
        else if (code == LookupResult.RESULT_KEY_REQUIRED)
            msg = _t("Base32 address requires encryption key");
        else if (code == LookupResult.RESULT_SECRET_AND_KEY_REQUIRED)
            msg = _t("Base32 address requires encryption key and lookup password");
        else if (code == LookupResult.RESULT_DECRYPTION_FAILURE)
            msg = _t("Base32 address decryption failure, check encryption key");
        else
            msg = "lookup failure code " + code;
        out.write("<p><b>" + msg + "</b></p>");
        out.write("<form method=\"GET\" action=\"http://" + LOCAL_SERVER + "/b32\">\n" +
                  "<input type=\"hidden\" name=\"host\" value=\"" + destination + "\">\n" +
                  "<input type=\"hidden\" name=\"url\" value=\"" + targetRequest + "\">\n" +
                  "<input type=\"hidden\" name=\"code\" value=\"" + code + "\">\n" +
                  "<input type=\"hidden\" name=\"nonce\" value=\"" + _proxyNonce + "\">\n");

        if (code == LookupResult.RESULT_KEY_REQUIRED || code == LookupResult.RESULT_SECRET_AND_KEY_REQUIRED) {
            String label = _t("Generate");
            out.write("<h4>" + _t("Encryption key") + "</h4>\n<p>" +
                      "<p>" + _t("You must either enter a PSK encryption key provided by the server operator, or generate a DH encryption key and send that to the server operator.") +
                      ' ' + _t("Ask the server operator for help.") +
                      "</p>\n" +

                      "<p><b>PSK:</b> " + _t("Enter PSK encryption key") +
                      ":</p>\n" +
                      "<input type=\"text\" size=\"55\" name=\"privkey\" value=\"\">\n" +
                      "<p><b>DH:</b> " + _t("Generate new DH encryption key") +
                      ": <div class=\"formaction_xx\">" +
                      "<button type=\"submit\" class=\"accept\" name=\"action\" value=\"newdh\">" + label +
                      "</button></div>\n");
                      //"<p>" + _t("Generate new PSK encryption key") +
                      //"<button type=\"submit\" class=\"accept\" name=\"action\" value=\"newpsk\">" + label + "</button>\n");
        }
        if (code == LookupResult.RESULT_SECRET_REQUIRED || code == LookupResult.RESULT_SECRET_AND_KEY_REQUIRED) {
            out.write("<h4>" + _t("Lookup password") + "</h4>\n<p>" +
                      "<p>" + _t("You must enter the password provided by the server operator.") +
                      "</p>\n" +
                      "<input type=\"text\" size=\"55\" name=\"secret\" value=\"\">\n");
        }

        // FIXME wasn't escaped
        String label = _t("Save & continue").replace("&", "&amp;");
        out.write("<p><div class=\"formaction\"><button type=\"submit\" class=\"accept\" name=\"action\" value=\"save\">" +
                  label + "</button></div>\n" +
                  "</form>\n</div>\n");
        writeFooter(out);
    }

    /**
     *  Read the first line unbuffered.
     *  After that, switch to a BufferedReader, unless the method is "POST".
     *  We can't use BufferedReader for POST because we can't have readahead,
     *  since we are passing the stream on to I2PTunnelRunner for the POST data.
     *
     *  Warning - BufferedReader removes \r, DataHelper does not
     *  Warning - DataHelper limits line length, BufferedReader does not
     *  Todo: Limit line length for buffered reads, or go back to unbuffered for all
     */
    public static class InputReader {
        InputStream _s;

        public InputReader(InputStream s) {
            _s = s;
        }

        public String readLine(String method) throws IOException {
            //  Use unbuffered until we can find a BufferedReader that limits line length
            //if (method == null || "POST".equals(method))
            return DataHelper.readLine(_s);
        //if (_br == null)
        //    _br = new BufferedReader(new InputStreamReader(_s, "ISO-8859-1"));
        //return _br.readLine();
        }

        /**
         *  Read the rest of the headers, which keeps firefox
         *  from complaining about connection reset after
         *  an error on the first line.
         *  @since 0.9.14
         */
        public void drain() {
            try {
                String line;
                do {
                    line = DataHelper.readLine(_s);
                    // \r not stripped so length == 1 is empty
                } while (line != null && line.length() > 1);
            } catch (IOException ioe) {}
        }
    }

    /**
     *  @return b32hash.b32.i2p, or "i2p" on lookup failure.
     *  Prior to 0.7.12, returned b64 key
     */
    private final String getHostName(String host) {
        if(host == null) {
            return null;
        }
        if (host.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
            return host;
        }
        Destination dest = _context.namingService().lookup(host);
        if (dest == null)
            return "i2p";
        return dest.toBase32();
    }

    public static final String DEFAULT_JUMP_SERVERS =
            //"http://i2host.i2p/cgi-bin/i2hostjump?," +
            "http://stats.i2p/cgi-bin/jump.cgi?a=," +
            //"http://no.i2p/jump/," +
            "http://i2pjump.i2p/jump/," +
            //"http://i2jump.i2p/";
            "http://notbob.i2p/cgi-bin/jump.cgi?q=";

    /** @param host ignored */
    private static boolean isSupportedAddress(String host, String protocol) {
        if((host == null) || (protocol == null)) {
            return false;
        }

        /****
         *  Let's not look up the name _again_
         *  and now that host is a b32, this was failing
         *
        boolean found = false;
        String lcHost = host.toLowerCase();
        for (int i = 0; i < SUPPORTED_HOSTS.length; i++) {
        if (SUPPORTED_HOSTS[i].equals(lcHost)) {
        found = true;
        break;
        }
        }

        if (!found) {
        try {
        Destination d = _context.namingService().lookup(host);
        if (d == null) return false;
        } catch (DataFormatException dfe) {
        }
        }
         ****/
        String lc = protocol.toLowerCase(Locale.US);
        return lc.equals("http") || lc.equals("https");
    }

    private final static String ERR_HELPER_DISABLED =
            "HTTP/1.1 403 Disabled\r\n" +
            "Content-Type: text/plain\r\n" +
            "Connection: close\r\n"+
            "\r\n" +
            "Address helpers disabled";

    /**
     *  Change various parts of the URI.
     *  String parameters are all non-encoded.
     *
     *  Scheme always preserved.
     *  Userinfo always cleared.
     *  Host changed if non-null.
     *  Port changed if non-zero.
     *  Path changed if non-null.
     *  Query always preserved.
     *  Fragment always cleared.
     *
     *  @since 0.9
     */
    private static URI changeURI(URI uri, String host, int port, String path) throws URISyntaxException {
        return new URI(uri.getScheme(),
                null,
                host != null ? host : uri.getHost(),
                port != 0 ? port : uri.getPort(),
                path != null ? path : uri.getPath(),
                // FIXME this breaks encoded =, &
                uri.getQuery(),
                null);
    }

    /**
     *  Replace query in the URI.
     *  Userinfo cleared if uri contained a query.
     *  Fragment cleared if uri contained a query.
     *
     *  @param query an ENCODED query, removed if null
     *  @since 0.9
     */
    private static URI replaceQuery(URI uri, String query) throws URISyntaxException {
        URI rv = uri;
        if(rv.getRawQuery() != null) {
            rv = new URI(rv.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null);
        }
        if(query != null) {
            String newURI = rv.toASCIIString() + '?' + query;
            rv = new URI(newURI);
        }
        return rv;
    }

    /**
     *  Remove the address helper from an encoded query.
     *
     *  @param query an ENCODED query, removed if null
     *  @return rv[0] is ENCODED query with helper removed, non-null but possibly empty;
     *          rv[1] is DECODED helper value, non-null but possibly empty;
     *          rv null if no helper present
     *  @since 0.9
     */
    private static String[] removeHelper(String query) {
        int keystart = 0;
        int valstart = -1;
        String key = null;
        for(int i = 0; i <= query.length(); i++) {
            char c = i < query.length() ? query.charAt(i) : '&';
            if(c == ';' || c == '&') {
                // end of key or value
                if(valstart < 0) {
                    key = query.substring(keystart, i);
                }
                String decodedKey = LocalHTTPServer.decode(key);
                if(decodedKey.equals(HELPER_PARAM)) {
                    String newQuery = keystart > 0 ? query.substring(0, keystart - 1) : "";
                    if(i < query.length() - 1) {
                        if(keystart > 0) {
                            newQuery += query.substring(i);
                        } else {
                            newQuery += query.substring(i + 1);
                        }
                    }
                    String value = valstart >= 0 ? query.substring(valstart, i) : "";
                    String helperValue = LocalHTTPServer.decode(value);
                    return new String[] {newQuery, helperValue};
                }
                keystart = i + 1;
                valstart = -1;
            } else if (c == '=' && valstart < 0) {
                // end of key
                key = query.substring(keystart, i);
                valstart = i + 1;
            }
        }
        return null;
    }

/****
    private static String[] tests = {
        "", "foo", "foo=bar", "&", "&=&", "===", "&&",
        "i2paddresshelper=foo",
        "i2paddresshelpe=foo",
        "2paddresshelper=foo",
        "i2paddresshelper=%66oo",
        "%692paddresshelper=foo",
        "i2paddresshelper=foo&a=b",
        "a=b&i2paddresshelper=foo",
        "a=b&i2paddresshelper&c=d",
        "a=b&i2paddresshelper=foo&c=d",
        "a=b;i2paddresshelper=foo;c=d",
        "a=b&i2paddresshelper=foo&c",
        "a=b&i2paddresshelper=foo==&c",
        "a=b&i2paddresshelper=foo%3d%3d&c",
        "a=b&i2paddresshelper=f%6f%6F==&c",
        "a=b&i2paddresshelper=foo&i2paddresshelper=bar&c",
        "a=b&i2paddresshelper=foo&c%3F%3f%26%3b%3B%3d%3Dc=x%3F%3f%26%3b%3B%3d%3Dx"
    };

    public static void main(String[] args) {
        for (int i = 0; i < tests.length; i++) {
            String[] s = removeHelper(tests[i]);
            if (s != null)
                System.out.println("Test \"" + tests[i] + "\" q=\"" + s[0] + "\" h=\"" + s[1] + "\"");
            else
                System.out.println("Test \"" + tests[i] + "\" no match");
        }
    }
****/
}
