package net.i2p.i2ptunnel.util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.app.ClientApp;
import net.i2p.app.ClientAppManager;
import net.i2p.app.Outproxy;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.I2PTunnelHTTPClient;
import net.i2p.i2ptunnel.localServer.LocalHTTPServer;
import net.i2p.util.ConvertToHash;
import net.i2p.util.Log;
import net.i2p.util.PortMapper;
import net.i2p.util.Translate;

public class HTTPRequestReader {
    private final I2PTunnel _tunnel;
    private final I2PTunnelHTTPClient _client;
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
    boolean shout = false;
    boolean isConnect = false;
    boolean isHead = false;
    I2PSocket i2ps = null;
    protected final Log _log;
    protected final I2PAppContext _context;

    public HTTPRequestReader(Socket s, I2PAppContext ctx, InputReader reader, boolean keepalive, AtomicLong __requestId,
            int requestCount, I2PTunnel tun, I2PTunnelHTTPClient client) throws IOException {
        _tunnel = tun;
        _client = client;
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        long requestId = __requestId.incrementAndGet();
        String line, method = null, protocol = null, host = null, destination = null;
        String hostLowerCase = null;
        StringBuilder newRequest = new StringBuilder();
        boolean ahelperPresent = false;
        boolean ahelperNew = false;
        String ahelperKey = null;
        String userAgent = null;
        String authorization = null;
        int remotePort = 0;
        String referer = null;
        URI origRequestURI = null;
        boolean preserveConnectionHeader = false;
        boolean allowGzip = false;
        OutputStream out = s.getOutputStream();
        while ((line = reader.readLine(method)) != null) {
            line = line.trim();
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(getPrefix(requestId) + "Line=[" + line + "]");
            }

            String lowercaseLine = line.toLowerCase(Locale.US);

            if (method == null) {
                // first line GET/POST/etc.
                if (_log.shouldInfo())
                    _log.info(getPrefix(requestId) + "req #" + requestCount + " first line [" + line + "]");

                String[] params = DataHelper.split(line, " ", 3);
                if (params.length != 3) {
                    break;
                }
                String request = params[1];

                // various obscure fixups
                if (request.startsWith("/")
                        && getTunnel().getClientOptions().getProperty("i2ptunnel.noproxy") != null) {
                    // what is this for ???
                    request = "http://i2p" + request;
                } else if (request.startsWith("/eepproxy/")) {
                    // Deprecated
                    // /eepproxy/foo.i2p/bar/baz.html
                    String subRequest = request.substring("/eepproxy/".length());
                    if (subRequest.indexOf('/') == -1) {
                        subRequest += '/';
                    }
                    request = "http://" + subRequest;
                    /****
                     * } else if (request.toLowerCase(Locale.US).startsWith("http://i2p/")) {
                     * // http://i2p/b64key/bar/baz.html
                     * // we can't do this now by setting the URI host to the b64key, as
                     * // it probably contains '=' and '~' which are illegal,
                     * // and a host may not include escaped octets
                     * // This will get undone below.
                     * String subRequest = request.substring("http://i2p/".length());
                     * if (subRequest.indexOf("/") == -1)
                     * subRequest += "/";
                     * "http://" + "b64key/bar/baz.html"
                     * request = "http://" + subRequest;
                     * } else if (request.toLowerCase(Locale.US).startsWith("http://")) {
                     * // Unsupported
                     * // http://$b64key/...
                     * // This probably used to work, rewrite it so that
                     * // we can create a URI without illegal characters
                     * // This will get undone below.
                     * String oldPath = request.substring(7);
                     * int slash = oldPath.indexOf("/");
                     * if (slash < 0)
                     * slash = oldPath.length();
                     * if (slash >= 516 && !oldPath.substring(0, slash).contains("."))
                     * request = "http://i2p/" + oldPath;
                     ****/
                }

                method = params[0].toUpperCase(Locale.US);
                if (method.equals("HEAD")) {
                    isHead = true;
                } else if (method.equals("CONNECT")) {
                    // this makes things easier later, by spoofing a
                    // protocol so the URI parser find the host and port
                    // For in-net outproxy, will be fixed up below
                    request = "https://" + request + '/';
                    isConnect = true;
                    keepalive = false;
                } else if (!method.equals("GET")) {
                    // POST, PUT, ...
                    keepalive = false;
                }

                // Now use the Java URI parser
                // This will be the incoming URI but will then get modified
                // to be the outgoing URI (with http:// if going to outproxy, otherwise without)
                URI requestURI = null;
                try {
                    try {
                        requestURI = new URI(request);
                    } catch (URISyntaxException use) {
                        // fixup []| in path/query not escaped by browsers, see ticket #2130
                        boolean error = true;
                        // find 3rd /
                        int idx = 0;
                        for (int i = 0; i < 2; i++) {
                            idx = request.indexOf('/', idx);
                            if (idx < 0)
                                break;
                            idx++;
                        }
                        if (idx > 0) {
                            String schemeHostPort = request.substring(0, idx);
                            String rest = request.substring(idx);
                            // not escaped by all browsers, may be specific to query, see ticket #2130
                            rest = rest.replace("[", "%5B");
                            rest = rest.replace("]", "%5D");
                            rest = rest.replace("|", "%7C");
                            rest = rest.replace("{", "%7B");
                            rest = rest.replace("}", "%7D");
                            String testRequest = schemeHostPort + rest;
                            if (!testRequest.equals(request)) {
                                try {
                                    requestURI = new URI(testRequest);
                                    request = testRequest;
                                    error = false;
                                } catch (URISyntaxException use2) {
                                    // didn't work, give up
                                }
                            }
                        }
                        // guess it wasn't []|
                        if (error)
                            throw use;
                    }
                    origRequestURI = requestURI;
                    if (requestURI.getRawUserInfo() != null || requestURI.getRawFragment() != null) {
                        // these should never be sent to the proxy in the request line
                        if (_log.shouldLog(Log.WARN)) {
                            _log.warn(getPrefix(requestId) + "Removing userinfo or fragment [" + request + "]");
                        }
                        requestURI = changeURI(requestURI, null, 0, null);
                    }
                    if (requestURI.getPath() == null || requestURI.getPath().length() <= 0) {
                        // Add a path
                        if (_log.shouldLog(Log.WARN)) {
                            _log.warn(getPrefix(requestId) + "Adding / path to [" + request + "]");
                        }
                        requestURI = changeURI(requestURI, null, 0, "/");
                    }
                } catch (URISyntaxException use) {
                    if (_log.shouldLog(Log.WARN)) {
                        _log.warn(getPrefix(requestId) + "Bad request [" + request + "]", use);
                    }
                    try {
                        out.write(getErrorPage("baduri", I2PTunnelHTTPClient.ERR_BAD_URI).getBytes("UTF-8"));
                        String msg = use.getLocalizedMessage();
                        if (msg != null) {
                            out.write(DataHelper.getASCII("<p>\n"));
                            out.write(DataHelper.getUTF8(DataHelper.escapeHTML(msg)));
                            out.write(DataHelper.getASCII("</p>\n"));
                        }
                        out.write(DataHelper.getASCII("</div>\n"));
                        writeFooter(out);
                        reader.drain();
                    } catch (IOException ioe) {
                        // ignore
                    }
                    return;
                }

                String protocolVersion = params[2];
                if (!protocolVersion.equals("HTTP/1.1"))
                    keepalive = false;

                protocol = requestURI.getScheme();
                host = requestURI.getHost();
                if (protocol == null || host == null) {
                    _log.warn("Null protocol or host: " + request + ' ' + protocol + ' ' + host);
                    method = null;
                    break;
                }

                int port = requestURI.getPort();

                // Go through the various types of hostnames, set
                // the host and destination variables accordingly,
                // and transform the first line.
                // For all i2p network hosts, ensure that the host is a
                // Base 32 hostname so that we do not reveal our name for it
                // in our addressbook (all naming is local),
                // and it is removed from the request line.

                hostLowerCase = host.toLowerCase(Locale.US);
                if (hostLowerCase.equals(I2PTunnelHTTPClient.LOCAL_SERVER)) {
                    // so we don't do any naming service lookups
                    destination = host;
                    usingInternalServer = true;
                    internalPath = requestURI.getPath();
                    internalRawQuery = requestURI.getRawQuery();
                } else if (hostLowerCase.equals("i2p")) {
                    // pull the b64 _dest out of the first path element
                    String oldPath = requestURI.getPath().substring(1);
                    int slash = oldPath.indexOf('/');
                    if (slash < 0) {
                        slash = oldPath.length();
                        oldPath += '/';
                    }
                    String _dest = oldPath.substring(0, slash);
                    if (slash >= 516 && !_dest.contains(".")) {
                        // possible alternative:
                        // redirect to b32
                        destination = _dest;
                        host = getHostName(destination);
                        targetRequest = requestURI.toASCIIString();
                        String newURI = oldPath.substring(slash);
                        String query = requestURI.getRawQuery();
                        if (query != null) {
                            newURI += '?' + query;
                        }
                        try {
                            requestURI = new URI(newURI);
                        } catch (URISyntaxException use) {
                            // shouldnt happen
                            _log.warn(request, use);
                            method = null;
                            break;
                        }
                    } else {
                        _log.warn("Bad http://i2p/b64dest " + request);
                        host = null;
                        break;
                    }
                } else if (hostLowerCase.endsWith(".i2p")) {
                    // Destination gets the hostname
                    destination = host;
                    // Host becomes the destination's "{b32}.b32.i2p" string, or "i2p" on lookup
                    // failure
                    host = getHostName(destination);

                    int rPort = requestURI.getPort();
                    if (rPort > 0) {
                        // Save it to put in the I2PSocketOptions,
                        remotePort = rPort;
                        /********
                         * // but strip it from the URL
                         * if(_log.shouldLog(Log.WARN)) {
                         * _log.warn(getPrefix(requestId) + "Removing port from [" + request + "]");
                         * }
                         * try {
                         * requestURI = changeURI(requestURI, null, -1, null);
                         * } catch(URISyntaxException use) {
                         * _log.warn(request, use);
                         * method = null;
                         * break;
                         * }
                         ******/
                    } else if ("https".equals(protocol) || isConnect) {
                        remotePort = 443;
                    } else {
                        remotePort = 80;
                    }

                    String query = requestURI.getRawQuery();
                    if (query != null) {
                        boolean ahelperConflict = false;

                        // Try to find an address helper in the query
                        String[] helperStrings = removeHelper(query);
                        if (helperStrings != null &&
                                !Boolean.parseBoolean(
                                        getTunnel().getClientOptions()
                                                .getProperty(I2PTunnelHTTPClient.PROP_DISABLE_HELPER))) {
                            query = helperStrings[0];
                            if (query.equals("")) {
                                query = null;
                            }
                            try {
                                requestURI = replaceQuery(requestURI, query);
                            } catch (URISyntaxException use) {
                                // shouldn't happen
                                _log.warn(request, use);
                                method = null;
                                break;
                            }
                            ahelperKey = helperStrings[1];
                            // Key contains data, lets not ignore it
                            if (ahelperKey.length() > 0) {
                                if (ahelperKey.endsWith(".i2p")) {
                                    // allow i2paddresshelper=<b32>.b32.i2p syntax.
                                    /*
                                     * also i2paddresshelper=name.i2p for aliases
                                     * i.e. on your I2P Site put
                                     * <a href="?i2paddresshelper=name.i2p">This is the name I want to be
                                     * called.</a>
                                     */
                                    Destination _dest = _context.namingService().lookup(ahelperKey);
                                    if (_dest == null) {
                                        if (_log.shouldLog(Log.WARN)) {
                                            _log.warn(getPrefix(requestId) + "Could not find destination for "
                                                    + ahelperKey);
                                        }
                                        String header = getErrorPage("ahelper-notfound",
                                                I2PTunnelHTTPClient.ERR_AHELPER_NOTFOUND);
                                        try {
                                            out.write(header.getBytes("UTF-8"));
                                            out.write(("<p>" + _t("This seems to be a bad destination:") + " "
                                                    + ahelperKey + " " +
                                                    _t("i2paddresshelper cannot help you with a destination like that!")
                                                    +
                                                    "</p>").getBytes("UTF-8"));
                                            writeFooter(out);
                                            reader.drain();
                                        } catch (IOException ioe) {
                                            // ignore
                                        }
                                        return;
                                    }
                                    ahelperKey = _dest.toBase64();
                                }

                                ahelperPresent = true;
                                // ahelperKey will be validated later
                                if (host == null || "i2p".equals(host)) {
                                    // Host lookup failed - resolvable only with addresshelper
                                    // Store in local HashMap unless there is conflict
                                    String old = _client.addressHelpers.putIfAbsent(destination.toLowerCase(Locale.US),
                                            ahelperKey);
                                    ahelperNew = old == null;
                                    // inr address helper links without trailing '=', so omit from comparison
                                    if ((!ahelperNew) && !old.replace("=", "").equals(ahelperKey.replace("=", ""))) {
                                        // Conflict: handle when URL reconstruction done
                                        ahelperConflict = true;
                                        if (_log.shouldLog(Log.WARN)) {
                                            _log.warn(getPrefix(requestId) + "Addresshelper key conflict for site ["
                                                    + destination +
                                                    "], trusted key [" + old + "], specified key [" + ahelperKey
                                                    + "].");
                                        }
                                    }
                                } else {
                                    // If the host is resolvable from database, verify addresshelper key
                                    // Silently bypass correct keys, otherwise alert
                                    Destination hostDest = _context.namingService().lookup(destination);
                                    if (hostDest != null) {
                                        String destB64 = hostDest.toBase64();
                                        if (destB64 != null && !destB64.equals(ahelperKey)) {
                                            // Conflict: handle when URL reconstruction done
                                            ahelperConflict = true;
                                            if (_log.shouldLog(Log.WARN)) {
                                                _log.warn(getPrefix(requestId) + "Addresshelper key conflict for site ["
                                                        + destination +
                                                        "], trusted key [" + destB64 + "], specified key [" + ahelperKey
                                                        + "].");
                                            }

                                        }
                                    }
                                }
                            } // ahelperKey
                        } // helperstrings

                        // Did addresshelper key conflict?
                        if (ahelperConflict) {
                            try {
                                // convert ahelperKey to b32
                                String alias = getHostName(ahelperKey);
                                if (alias.equals("i2p")) {
                                    // bad ahelperKey
                                    String header = getErrorPage("dnfb", I2PTunnelHTTPClient.ERR_DESTINATION_UNKNOWN);
                                    _client.writeErrorMessage(header, out, targetRequest, false, destination);
                                } else {
                                    String trustedURL = requestURI.toASCIIString();
                                    URI conflictURI;
                                    try {
                                        conflictURI = changeURI(requestURI, alias, 0, null);
                                    } catch (URISyntaxException use) {
                                        // shouldn't happen
                                        _log.warn(request, use);
                                        method = null;
                                        break;
                                    }
                                    String conflictURL = conflictURI.toASCIIString();
                                    String header = getErrorPage("ahelper-conflict",
                                            I2PTunnelHTTPClient.ERR_AHELPER_CONFLICT);
                                    out.write(header.getBytes("UTF-8"));
                                    out.write("<p>".getBytes("UTF-8"));
                                    out.write(_t(
                                            "To visit the destination in your address book, click <a href=\"{0}\">here</a>. To visit the conflicting addresshelper destination, click <a href=\"{1}\">here</a>.",
                                            trustedURL, conflictURL).getBytes("UTF-8"));
                                    out.write("</p>".getBytes("UTF-8"));
                                    Hash h1 = ConvertToHash.getHash(requestURI.getHost());
                                    Hash h2 = ConvertToHash.getHash(ahelperKey);
                                    if (h1 != null && h2 != null) {
                                        String conURL = _context.portMapper().getConsoleURL();
                                        out.write(("\n<table class=\"conflict\"><tr><th align=\"center\">" +
                                                "<a href=\"" + trustedURL + "\">").getBytes("UTF-8"));
                                        out.write(_t("Destination for {0} in address book", requestURI.getHost())
                                                .getBytes("UTF-8"));
                                        out.write(("</a></th>\n<th align=\"center\">" +
                                                "<a href=\"" + conflictURL + "\">").getBytes("UTF-8"));
                                        out.write(_t("Conflicting address helper destination").getBytes("UTF-8"));
                                        out.write(("</a></th></tr>\n").getBytes("UTF-8"));
                                        if (_context.portMapper().isRegistered(PortMapper.SVC_IMAGEGEN)) {
                                            out.write(("<tr><td align=\"center\">" +
                                                    "<a href=\"" + trustedURL + "\">" +
                                                    "<img src=\"" +
                                                    conURL + "imagegen/id?s=160&amp;c=" +
                                                    h1.toBase64().replace("=", "%3d") +
                                                    "\" width=\"160\" height=\"160\"></a>\n" +
                                                    "</td>\n<td align=\"center\">" +
                                                    "<a href=\"" + conflictURL + "\">" +
                                                    "<img src=\"" +
                                                    conURL + "imagegen/id?s=160&amp;c=" +
                                                    h2.toBase64().replace("=", "%3d") +
                                                    "\" width=\"160\" height=\"160\"></a>\n" +
                                                    "</td></tr>").getBytes("UTF-8"));
                                        }
                                        out.write("</table>".getBytes("UTF-8"));
                                    }
                                    out.write("</div>".getBytes("UTF-8"));
                                    writeFooter(out);
                                }
                                reader.drain();
                            } catch (IOException ioe) {
                                // ignore
                            }
                            return;
                        }
                    } // end query processing

                    String addressHelper = _client.addressHelpers.get(destination);
                    if (addressHelper != null) {
                        host = getHostName(addressHelper);
                    }

                    targetRequest = requestURI.toASCIIString();
                    if (!isConnect) {
                        // now strip everything but path and query from URI
                        String newURI = requestURI.getRawPath();
                        if (query != null) {
                            newURI += '?' + query;
                        }
                        try {
                            requestURI = new URI(newURI);
                        } catch (URISyntaxException use) {
                            // shouldnt happen
                            _log.warn(request, use);
                            method = null;
                            break;
                        }
                    }

                    // end of (host endsWith(".i2p"))

                } else if (hostLowerCase.equals("localhost") || host.equals("127.0.0.1") ||
                        host.startsWith("192.168.") || host.equals("[::1]")) {
                    // if somebody is trying to get to 192.168.example.com, oh well
                    try {
                        out.write(getErrorPage("localhost", I2PTunnelHTTPClient.ERR_LOCALHOST).getBytes("UTF-8"));
                        writeFooter(out);
                        reader.drain();
                    } catch (IOException ioe) {
                        // ignore
                    }
                    return;
                } else if (host.contains(".") || host.startsWith("[")) {
                    if (Boolean.parseBoolean(
                            getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_USE_OUTPROXY_PLUGIN,
                                    "true"))) {
                        ClientAppManager mgr = _context.clientAppManager();
                        if (mgr != null) {
                            ClientApp op = mgr.getRegisteredApp(Outproxy.NAME);
                            if (op != null) {
                                outproxy = (Outproxy) op;
                                int rPort = requestURI.getPort();
                                if (rPort > 0)
                                    remotePort = rPort;
                                else if ("https".equals(protocol) || isConnect)
                                    remotePort = 443;
                                else
                                    remotePort = 80;
                                usingInternalOutproxy = true;
                                targetRequest = requestURI.toASCIIString();
                                if (_log.shouldLog(Log.DEBUG))
                                    _log.debug(getPrefix(requestId) + " [" + host + "]: outproxy!");
                            }
                        }
                    }
                    if (!usingInternalOutproxy) {
                        if (port >= 0) {
                            host = host + ':' + port;
                        }
                        // The request must be forwarded to a WWW proxy
                        if (_log.shouldLog(Log.DEBUG)) {
                            _log.debug("Before selecting outproxy for " + host);
                        }
                        if ("https".equals(protocol) || isConnect)
                            currentProxy = _client.selectSSLProxy(hostLowerCase);
                        else
                            currentProxy = _client.selectProxy(hostLowerCase);
                        if (_log.shouldLog(Log.DEBUG)) {
                            _log.debug("After selecting outproxy for " + host + ": " + currentProxy);
                        }
                        if (currentProxy == null) {
                            if (_log.shouldLog(Log.WARN)) {
                                _log.warn("No outproxy configured for request: " + requestURI);
                            }
                            try {
                                out.write(
                                        getErrorPage("noproxy", I2PTunnelHTTPClient.ERR_NO_OUTPROXY).getBytes("UTF-8"));
                                writeFooter(out);
                                reader.drain();
                            } catch (IOException ioe) {
                                // ignore
                            }
                            return;
                        }
                        destination = currentProxy;
                        usingWWWProxy = true;
                        targetRequest = requestURI.toASCIIString();
                        if (_log.shouldLog(Log.DEBUG)) {
                            _log.debug(getPrefix(requestId) + " [" + host + "]: wwwProxy!");
                        }
                    }
                } else {
                    // what is left for here? a hostname with no dots, and != "i2p"
                    // and not a destination ???
                    // Perhaps something in privatehosts.txt ...
                    // Rather than look it up, just bail out.
                    if (_log.shouldLog(Log.WARN)) {
                        _log.warn("NODOTS, NOI2P: " + request);
                    }
                    try {
                        out.write(getErrorPage("denied", I2PTunnelHTTPClient.ERR_REQUEST_DENIED).getBytes("UTF-8"));
                        writeFooter(out);
                        reader.drain();
                    } catch (IOException ioe) {
                        // ignore
                    }
                    return;
                } // end hostname processing

                boolean isValid = usingInternalOutproxy || usingWWWProxy ||
                        usingInternalServer || isSupportedAddress(host, protocol);
                if (!isValid) {
                    if (_log.shouldLog(Log.INFO)) {
                        _log.info(getPrefix(requestId) + "notValid(" + host + ")");
                    }
                    method = null;
                    destination = null;
                    break;
                }

                if (isConnect) {
                    // fix up the change to requestURI above to get back to the original host:port
                    if (usingInternalOutproxy || usingWWWProxy)
                        line = method + ' ' + requestURI.getHost() + ':' + requestURI.getPort() + ' ' + protocolVersion;
                    else
                        line = method + ' ' + host + ':' + remotePort + ' ' + protocolVersion;
                } else {
                    line = method + ' ' + requestURI.toASCIIString() + ' ' + protocolVersion;
                }

                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug(getPrefix(requestId) + "REQ   : \"" + request + "\"");
                    _log.debug(getPrefix(requestId) + "REQURI: \"" + requestURI + "\"");
                    _log.debug(getPrefix(requestId) + "NEWREQ: \"" + line + "\"");
                    _log.debug(getPrefix(requestId) + "HOST  : \"" + host + "\"");
                    _log.debug(getPrefix(requestId) + "RPORT : \"" + remotePort + "\"");
                    _log.debug(getPrefix(requestId) + "DEST  : \"" + destination + "\"");
                }

                // end first line processing

            } else {
                if (lowercaseLine.startsWith("connection: ")) {
                    if (lowercaseLine.contains("upgrade")) {
                        // pass through for websocket
                        preserveConnectionHeader = true;
                        keepalive = false;
                    } else if (lowercaseLine.contains("keep-alive")) {
                        // pass through
                        if (!keepalive)
                            continue;
                        // pass through
                        preserveConnectionHeader = true;
                    } else {
                        if (lowercaseLine.contains("close"))
                            keepalive = false;
                        continue;
                    }
                } else if (lowercaseLine.startsWith("keep-alive: ") ||
                        lowercaseLine.startsWith("proxy-connection: ")) {
                    if (lowercaseLine.contains("close"))
                        keepalive = false;
                    continue;
                } else if (lowercaseLine.startsWith("host: ") && !usingWWWProxy && !usingInternalOutproxy) {
                    // Note that we only pass the original Host: line through to the outproxy
                    // But we don't create a Host: line if it wasn't sent to us
                    line = "Host: " + host;
                    if (_log.shouldDebug()) {
                        _log.debug(getPrefix(requestId) + "Setting host = " + host);
                    }
                } else if (lowercaseLine.startsWith("user-agent: ")) {
                    // save for deciding whether to offer address book form
                    userAgent = lowercaseLine.substring(12);
                    if (!Boolean.parseBoolean(
                            getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_USER_AGENT))) {
                        line = null;
                        continue;
                    }
                } else if (lowercaseLine.startsWith("accept: ")) {
                    if (!Boolean.parseBoolean(
                            getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_ACCEPT))) {
                        // Replace with a standard one if possible
                        boolean html = lowercaseLine.indexOf("text/html") > 0;
                        boolean css = lowercaseLine.indexOf("text/css") > 0;
                        boolean img = lowercaseLine.indexOf("image") > 0;
                        if (html && !img && !css) {
                            // firefox, tor browser
                            line = "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
                        } else if (img && !html && !css) {
                            // chrome
                            line = "Accept: image/webp,image/apng,image/*,*/*;q=0.8";
                        } else if (css && !html && !img) {
                            // chrome, firefox
                            line = "Accept: text/css,*/*;q=0.1";
                        } // else allow as-is
                    }
                } else if (lowercaseLine.startsWith("accept")) {
                    // strip the accept-blah headers, as they vary dramatically from
                    // browser to browser
                    // But allow Accept-Encoding: gzip, deflate
                    if (lowercaseLine.startsWith("accept-encoding: ")) {
                        allowGzip = lowercaseLine.contains("gzip");
                    } else if (!Boolean.parseBoolean(
                            getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_ACCEPT))) {
                        line = null;
                        continue;
                    }
                } else if (lowercaseLine.startsWith("referer: ")) {
                    // save for address helper form below
                    referer = line.substring(9);
                    if (!Boolean.parseBoolean(
                            getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_REFERER))) {
                        try {
                            // Either strip or rewrite the referer line
                            URI refererURI = new URI(referer);
                            String refererHost = refererURI.getHost();
                            if (refererHost != null) {
                                String origHost = origRequestURI.getHost();
                                if (!refererHost.equals(origHost) ||
                                        refererURI.getPort() != origRequestURI.getPort() ||
                                        !DataHelper.eq(refererURI.getScheme(), origRequestURI.getScheme())) {
                                    line = null;
                                    continue; // completely strip the line if everything doesn't match
                                }
                                // Strip to a relative URI, to hide the original hostname
                                StringBuilder buf = new StringBuilder();
                                buf.append("Referer: ");
                                String refererPath = refererURI.getRawPath();
                                buf.append(refererPath != null ? refererPath : "/");
                                String refererQuery = refererURI.getRawQuery();
                                if (refererQuery != null)
                                    buf.append('?').append(refererQuery);
                                line = buf.toString();
                            } // else relative URI, leave in
                        } catch (URISyntaxException use) {
                            line = null;
                            continue; // completely strip the line
                        }
                    } // else allow
                } else if (lowercaseLine.startsWith("via: ") &&
                        !Boolean.parseBoolean(
                                getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_VIA))) {
                    // line = "Via: i2p";
                    line = null;
                    continue; // completely strip the line
                } else if (lowercaseLine.startsWith("from: ")) {
                    // line = "From: i2p";
                    line = null;
                    continue; // completely strip the line
                } else if (lowercaseLine.startsWith("authorization: ntlm ")) {
                    // Block Windows NTLM after 401
                    line = null;
                    continue;
                } else if (lowercaseLine.startsWith("proxy-authorization: ")) {
                    // This should be for us. It is a
                    // hop-by-hop header, and we definitely want to block Windows NTLM after a
                    // far-end 407.
                    // Response to far-end shouldn't happen, as we
                    // strip Proxy-Authenticate from the response in HTTPResponseOutputStream
                    authorization = line.substring(21); // "proxy-authorization: ".length()
                    line = null;
                    continue;
                } else if (lowercaseLine.startsWith("icy")) {
                    // icecast/shoutcast, We need to leave the user-agent alone.
                    shout = true;
                }
            }

            if (line.length() == 0) {
                // No more headers, add our own and break out of the loop
                String ok = getTunnel().getClientOptions().getProperty("i2ptunnel.gzip");
                boolean gzip = I2PTunnelHTTPClient.DEFAULT_GZIP;
                if (ok != null) {
                    gzip = Boolean.parseBoolean(ok);
                }
                if (gzip && !usingInternalServer && !isConnect) {
                    // according to rfc2616 s14.3, this *should* force identity, even if
                    // an explicit q=0 for gzip doesn't. tested against orion.i2p, and it
                    // seems to work.
                    // if
                    // (!Boolean.parseBoolean(getTunnel().getClientOptions().getProperty(PROP_ACCEPT)))
                    // newRequest.append("Accept-Encoding: \r\n");
                    if (!usingInternalOutproxy)
                        newRequest.append(
                                "X-Accept-Encoding: x-i2p-gzip;q=1.0, identity;q=0.5, deflate;q=0, gzip;q=0, *;q=0\r\n");
                }
                if (!shout && !isConnect) {
                    if (!Boolean.parseBoolean(
                            getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_USER_AGENT))) {
                        // let's not advertise to external sites that we are from I2P
                        String ua;
                        if (usingWWWProxy || usingInternalOutproxy) {
                            ua = getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_UA_CLEARNET);
                            if (ua != null)
                                ua = "User-Agent: " + ua + "\r\n";
                            else
                                ua = I2PTunnelHTTPClient.UA_CLEARNET;
                        } else {
                            ua = getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_UA_I2P);
                            if (ua != null)
                                ua = "User-Agent: " + ua + "\r\n";
                            else
                                ua = I2PTunnelHTTPClient.UA_I2P;
                        }
                        newRequest.append(ua);
                    }
                }
                // Add Proxy-Authentication header for next hop (outproxy)
                if (usingWWWProxy
                        && Boolean.parseBoolean(
                                getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_OUTPROXY_AUTH))) {
                    // specific for this proxy
                    String user = getTunnel().getClientOptions()
                            .getProperty(I2PTunnelHTTPClient.PROP_OUTPROXY_USER_PREFIX + currentProxy);
                    String pw = getTunnel().getClientOptions()
                            .getProperty(I2PTunnelHTTPClient.PROP_OUTPROXY_PW_PREFIX + currentProxy);
                    if (user == null || pw == null) {
                        // if not, look at default user and pw
                        user = getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_OUTPROXY_USER);
                        pw = getTunnel().getClientOptions().getProperty(I2PTunnelHTTPClient.PROP_OUTPROXY_PW);
                    }
                    if (user != null && pw != null) {
                        newRequest.append("Proxy-Authorization: Basic ")
                                .append(Base64.encode((user + ':' + pw).getBytes("UTF-8"), true)) // true = use standard
                                                                                                  // alphabet
                                .append("\r\n");
                    }
                }
                if (preserveConnectionHeader)
                    newRequest.append("\r\n");
                else
                    newRequest.append("Connection: close\r\n\r\n");
                s.setSoTimeout(I2PTunnelHTTPClient.BROWSER_READ_TIMEOUT);
                break;
            } else {
                newRequest.append(line).append("\r\n"); // HTTP spec
            }
        }
    }

    /**
     * Change various parts of the URI.
     * String parameters are all non-encoded.
     *
     * Scheme always preserved.
     * Userinfo always cleared.
     * Host changed if non-null.
     * Port changed if non-zero.
     * Path changed if non-null.
     * Query always preserved.
     * Fragment always cleared.
     *
     * @since 0.9
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

    private long _clientId() {
        return _client.getClientId();
    }

    protected String getPrefix(long requestId) {
        return "HTTPClient[" + _clientId()+ '/' + requestId + "]: ";
    }

    /** @param host ignored */
    private static boolean isSupportedAddress(String host, String protocol) {
        if ((host == null) || (protocol == null)) {
            return false;
        }

        /****
         * Let's not look up the name _again_
         * and now that host is a b32, this was failing
         *
         * boolean found = false;
         * String lcHost = host.toLowerCase();
         * for (int i = 0; i < SUPPORTED_HOSTS.length; i++) {
         * if (SUPPORTED_HOSTS[i].equals(lcHost)) {
         * found = true;
         * break;
         * }
         * }
         *
         * if (!found) {
         * try {
         * Destination d = _context.namingService().lookup(host);
         * if (d == null) return false;
         * } catch (DataFormatException dfe) {
         * }
         * }
         ****/
        String lc = protocol.toLowerCase(Locale.US);
        return lc.equals("http") || lc.equals("https");
    }

    /**
     * Flushes.
     *
     * Public only for LocalHTTPServer, not for general use
     *
     * @since 0.9.14 moved from I2PTunnelHTTPClient
     */
    public static void writeFooter(OutputStream out) throws IOException {
        out.write(I2PTunnelHTTPClient.getFooter().getBytes("UTF-8"));
        out.flush();
    }

    /**
     * @return b32hash.b32.i2p, or "i2p" on lookup failure.
     *         Prior to 0.7.12, returned b64 key
     */
    private final String getHostName(String host) {
        if (host == null) {
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

    public I2PTunnel getTunnel() {
        return _tunnel;
    }

    /**
     * Remove the address helper from an encoded query.
     *
     * @param query an ENCODED query, removed if null
     * @return rv[0] is ENCODED query with helper removed, non-null but possibly
     *         empty;
     *         rv[1] is DECODED helper value, non-null but possibly empty;
     *         rv null if no helper present
     * @since 0.9
     */
    private static String[] removeHelper(String query) {
        int keystart = 0;
        int valstart = -1;
        String key = null;
        for (int i = 0; i <= query.length(); i++) {
            char c = i < query.length() ? query.charAt(i) : '&';
            if (c == ';' || c == '&') {
                // end of key or value
                if (valstart < 0) {
                    key = query.substring(keystart, i);
                }
                String decodedKey = LocalHTTPServer.decode(key);
                if (decodedKey.equals(I2PTunnelHTTPClient.HELPER_PARAM)) {
                    String newQuery = keystart > 0 ? query.substring(0, keystart - 1) : "";
                    if (i < query.length() - 1) {
                        if (keystart > 0) {
                            newQuery += query.substring(i);
                        } else {
                            newQuery += query.substring(i + 1);
                        }
                    }
                    String value = valstart >= 0 ? query.substring(valstart, i) : "";
                    String helperValue = LocalHTTPServer.decode(value);
                    return new String[] { newQuery, helperValue };
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

    /**
     * Replace query in the URI.
     * Userinfo cleared if uri contained a query.
     * Fragment cleared if uri contained a query.
     *
     * @param query an ENCODED query, removed if null
     * @since 0.9
     */
    private static URI replaceQuery(URI uri, String query) throws URISyntaxException {
        URI rv = uri;
        if (rv.getRawQuery() != null) {
            rv = new URI(rv.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null);
        }
        if (query != null) {
            String newURI = rv.toASCIIString() + '?' + query;
            rv = new URI(newURI);
        }
        return rv;
    }

    /**
     * Translate
     *
     * @since 0.9.14 moved from I2PTunnelHTTPClient
     */
    protected String _t(String key) {
        return Translate.getString(key, _context, I2PTunnelHTTPClient.BUNDLE_NAME);
    }

    /**
     * Translate
     * {0}
     *
     * @since 0.9.14 moved from I2PTunnelHTTPClient
     */
    protected String _t(String key, Object o) {
        return Translate.getString(key, o, _context, I2PTunnelHTTPClient.BUNDLE_NAME);
    }

    /**
     * Translate
     * {0} and {1}
     *
     * @since 0.9.14 moved from I2PTunnelHTTPClient
     */
    protected String _t(String key, Object o, Object o2) {
        return Translate.getString(key, o, o2, _context, I2PTunnelHTTPClient.BUNDLE_NAME);
    }

    /**
     * foo =&gt; errordir/foo-header_xx.ht for lang xx, or errordir/foo-header.ht,
     * or the backup byte array on fail.
     *
     * .ht files must be UTF-8 encoded and use \r\n terminators so the
     * HTTP headers are conformant.
     * We can't use FileUtil.readFile() because it strips \r
     *
     * @return non-null
     * @since 0.9.4 moved from I2PTunnelHTTPClient
     */
    protected String getErrorPage(String base, String backup) {
        return I2PTunnelHTTPClient.getErrorPage(_context, base, backup);
    }

    public String toString() {
        return targetRequest;
    }
}
