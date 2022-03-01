package net.i2p.router.networkdb.reseed;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.Addresses;
import net.i2p.util.AddressType;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;
import static net.i2p.socks.SOCKS5Constants.*;

/**
 * Moved from RouterConsoleRunner.java
 *
 * Reseeding is not strictly a router function, it used to be
 * in the routerconsole app, but this made it impossible to
 * bootstrap an embedded router lacking a routerconsole,
 * in iMule or android for example, without additional modifications.
 *
 * Also, as this is now called from PersistentDataStore, not from the
 * routerconsole, we can get started as soon as the netdb has read
 * the netDb/ directory, not when the console starts.
 */
public class ReseedChecker {

    private final RouterContext _context;
    private final Log _log;
    private final AtomicBoolean _inProgress = new AtomicBoolean();
    private volatile String _torHost = "127.0.0.1";
    private volatile int _torSOCKSPort = 9050;
    private volatile String _lastStatus = "";
    private volatile String _lastError = "";
    private volatile boolean _networkLogged;
    private volatile boolean _alreadyRun;

    public static final int MINIMUM = 50;
    private static final long STATUS_CLEAN_TIME = 20 * 60 * 1000;
    // if down this long, reseed at startup
    private static final long RESEED_MIN_DOWNTIME = 60 * 24 * 60 * 60 * 1000L;

    /**
     * All reseeding must be done through this instance.
     * Access through context.netDb().reseedChecker(), others should not instantiate
     *
     * @since 0.9
     */
    public ReseedChecker(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(ReseedChecker.class);
    }

    /**
     * Check if a reseed is needed, and start it
     *
     * @param count current number of known routers, includes us
     * @return true if a reseed was started
     */
    public boolean checkReseed(int count) {
        if (_alreadyRun) {
            if (count >= MINIMUM)
                return false;
        } else {
            _alreadyRun = true;
            if (count >= MINIMUM && _context.getEstimatedDowntime() < RESEED_MIN_DOWNTIME)
                return false;
        }

        if (_context.getBooleanProperty(Reseeder.PROP_DISABLE) ||
                _context.getBooleanProperty("i2p.vmCommSystem")) {
            int x = count - 1; // us
            // no ngettext, this is rare
            String s;
            if (x > 0)
                s = "Only " + x + " peers remaining but reseed disabled by configuration";
            else
                s = "No peers remaining but reseed disabled by configuration";
            if (!s.equals(_lastError)) {
                _lastError = s;
                _log.logAlways(Log.WARN, s);
            }
            return false;
        }

        if (_context.router().gracefulShutdownInProgress()) {
            int x = count - 1;
            // no ngettext, this is rare
            String s;
            if (x > 0)
                s = "Only " + x + " peers remaining but reseed disabled by shutdown in progress";
            else
                s = "No peers remaining but reseed disabled by shutdown in progress";
            if (!s.equals(_lastError)) {
                _lastError = s;
                _log.logAlways(Log.WARN, s);
            }
            return false;
        }

        // we check the i2p installation directory for a flag telling us not to reseed,
        // but also check the home directory for that flag too, since new users
        // installing i2p
        // don't have an installation directory that they can put the flag in yet.
        File noReseedFile = new File(new File(System.getProperty("user.home")), ".i2pnoreseed");
        File noReseedFileAlt1 = new File(new File(System.getProperty("user.home")), "noreseed.i2p");
        File noReseedFileAlt2 = new File(_context.getConfigDir(), ".i2pnoreseed");
        File noReseedFileAlt3 = new File(_context.getConfigDir(), "noreseed.i2p");
        if (!noReseedFile.exists() && !noReseedFileAlt1.exists() && !noReseedFileAlt2.exists() && !noReseedFileAlt3.exists()) {
            Set<AddressType> addrs = Addresses.getConnectedAddressTypes();
            if (!addrs.contains(AddressType.IPV4) && !addrs.contains(AddressType.IPV6)) {
                if (!_networkLogged) {
                    _log.logAlways(Log.WARN, "Cannot reseed, no network connection");
                    _networkLogged = true;
                }
                return false;
            }
            _networkLogged = false;
            if (count <= 1)
                _log.logAlways(Log.INFO, "Downloading peer router information for a new I2P installation");
            else
                _log.logAlways(Log.WARN, "Very few known peers remaining - reseeding now");
            return requestReseed();
        } else {
            int x = count - 1; // us
            // no ngettext, this is rare
            String s;
            if (x > 0)
                s = "Only " + x + " peers remaining but reseed disabled by config file";
            else
                s = "No peers remaining but reseed disabled by config file";
            if (!s.equals(_lastError)) {
                _lastError = s;
                _log.logAlways(Log.WARN, s);
            }
            return false;
        }
    }

    /**
     * Start a reseed
     *
     * @return true if a reseed was started, false if already in progress
     * @since 0.9
     */
    public boolean requestReseed() {
        if (_inProgress.compareAndSet(false, true)) {
            _alreadyRun = true;
            try {
                Reseeder reseeder = new Reseeder(_context, this);
                reseeder.requestReseed();
                return true;
            } catch (Throwable t) {
                _log.error("Reseed failed to start", t);
                done();
                return false;
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Reseed already in progress");
            return false;
        }
    }

    /**
     * Start a reseed from the onion URL pool
     *
     * @return true if a reseed was started, false if already in progress
     * @since 0.9.53
     */
    public boolean requestOnionReseed(){
        if (!onionReseedsConfigured())
            return false;
        if (_inProgress.compareAndSet(false, true)) {
            _alreadyRun = true;
            try {
                Reseeder reseeder = new Reseeder(_context, this);
                reseeder.requestOnionReseed();
                return true;
            } catch (Throwable t) {
                _log.error("Reseed failed to start", t);
                done();
                return false;
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Reseed already in progress");
            return false;
        }
    }

    /**
     * Determine if a list of onion reseeds are configured with i2p.onionReseedURL
     *
     * @return true if at least one onion reseed is configured.
     * @since 0.9.53
     */
    public boolean onionReseedsConfigured() {
        String url = _context.getProperty(Reseeder.PROP_ONION_RESEED_URL);
        if (url == null)
            return false;
        if (url.length() < 1)
            return false;
        return true;
    }

    /**
     * Start a reseed from a zip or su3 URI.
     *
     * @return true if a reseed was started, false if already in progress
     * @throws IllegalArgumentException if it doesn't end with zip or su3
     * @since 0.9.19
     */
    public boolean requestReseed(String uri) {
        URI newURI = URI.create(uri);
        return requestReseed(newURI, null, -1);
    }

    /**
     * Start a reseed from a zip or su3 URI.
     * If Tor is available, use it, otherwise return false.
     *
     * @return true if a reseed was started, false if already in progress
     * @throws IllegalArgumentException if it doesn't end with zip or su3
     * @since 0.9.52
     */
    public boolean requestOnionReseed(URI uri) {
        int proxyPort = torSOCKSPort();
        String proxyHost = torHost();
        if (testTor(proxyHost, proxyPort)) {
            return requestReseed(uri, proxyHost, proxyPort);
        } else {
            return false;
        }
    }

    private boolean testTor() {
        return testTor(this._torHost, this._torSOCKSPort);
    }

    private boolean testTor(String phost, int pport) { // throws IOException {
        // test that the socks 5 proxy is there and auth, if any, works
        Socket s = null;
        OutputStream out = null;
        InputStream in = null;
        String _phost = phost;
        int _pport = pport;
        String _puser = ""; //"reseed";
        String _ppw = "";
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(_phost, _pport), 10 * 1000);
            out = s.getOutputStream();
            boolean authAvail = _puser != null && _ppw != null;

            // send the init
            out.write(SOCKS_VERSION_5);
            if (authAvail) {
                out.write(2);
                out.write(Method.USERNAME_PASSWORD);
            } else {
                out.write(1);
            }
            out.write(Method.NO_AUTH_REQUIRED);
            out.flush();

            // read init reply
            in = s.getInputStream();
            int hisVersion = in.read();
            if (hisVersion != SOCKS_VERSION_5)
                return false;

            int method = in.read();
            if (method == Method.NO_AUTH_REQUIRED) {
                // good
            } else if (method == Method.USERNAME_PASSWORD) {
                if (authAvail) {
                    // send the auth
                    out.write(AUTH_VERSION);
                    byte[] user = _puser.getBytes("UTF-8");
                    byte[] pw = _ppw.getBytes("UTF-8");
                    out.write(user.length);
                    out.write(user);
                    out.write(pw.length);
                    out.write(pw);
                    out.flush();
                    // read the auth reply
                    if (in.read() != AUTH_VERSION)
                        return false;
                    if (in.read() != AUTH_SUCCESS)
                        return false;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        } catch (IOException ioe) {
            return false;
        } finally {
            if (in != null)
                try {
                    in.close();
                } catch (IOException ioe) {
                    return false;
                }
            if (out != null)
                try {
                    out.close();
                } catch (IOException ioe) {
                    return false;
                }
            if (s != null)
                try {
                    s.close();
                } catch (IOException ioe) {
                    return false;
                }
        }
        return true;
    }

    /**
     * Detect if Tor is running on the host. If it is, return the hostname.
     *
     * @return hostname of Tor, or null if Tor is not running
     * @since 0.9.52
     */
    public String torHost() {
        if (testTor()) {
            _torHost = "127.0.0.1";
        }
        return _torHost;
    }

    /**
     * Detect if Tor is running on the host. If it is, return the SOCKSport
     *
     * @return SOCKSport of Tor, or -1 if Tor is not running
     * @since 0.9.52
     */
    public int torSOCKSPort() {
        if (testTor()) {
            _torSOCKSPort = 9050;
        }
        return _torSOCKSPort;
    }

    /**
     * Start a reseed from a zip or su3 URI.
     *
     * @return true if a reseed was started, false if already in progress
     * @throws IllegalArgumentException if it doesn't end with zip or su3
     * @since 0.9.52
     */
    public boolean requestReseed(URI uri) {
        return requestReseed(uri, null, -1);
    }

    public boolean requestReseed(URI url, String proxyHost, int proxyPort) throws IllegalArgumentException {
        if (url.getHost().endsWith(".onion")) {
            if (proxyHost == null && testTor()) {
                proxyHost = torHost();
            } else {
                throw new IllegalArgumentException("Onion reseed requires a proxy host");
            }
            if (proxyPort <= 0 && testTor()) {
                proxyPort = torSOCKSPort();
            } else {
                throw new IllegalArgumentException("Onion reseed requires a proxy port");
            }
        }
        if (_inProgress.compareAndSet(false, true)) {
            Reseeder reseeder = new Reseeder(_context, this);
            try {
                reseeder.requestReseed(url, proxyHost, proxyPort);
                return true;
            } catch (IllegalArgumentException iae) {
                if (iae.getMessage() != null)
                    setError(DataHelper.escapeHTML(iae.getMessage()));
                done();
                throw iae;
            } catch (Throwable t) {
                _log.error("Reseed failed to start", t);
                done();
                return false;
            }
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Reseed already in progress");
            return false;
        }
    }

    /**
     * Reseed from a zip or su3 input stream. Blocking.
     *
     * @return true if a reseed was started, false if already in progress
     * @throws IOException if already in progress or on most other errors
     * @since 0.9.19
     */
    public int requestReseed(InputStream in) throws IOException {
        // don't really need to check for in progress here
        if (_inProgress.compareAndSet(false, true)) {
            try {
                Reseeder reseeder = new Reseeder(_context, this);
                return reseeder.requestReseed(in);
            } catch (IOException ioe) {
                if (ioe.getMessage() != null)
                    setError(DataHelper.escapeHTML(ioe.getMessage()));
                throw ioe;
            } finally {
                done();
            }
        } else {
            throw new IOException("Reseed already in progress");
        }
    }

    /**
     * .
     * Is a reseed in progress?
     *
     * @since 0.9
     */
    public boolean inProgress() {
        return _inProgress.get();
    }

    /**
     * The reseed is complete
     *
     * @since 0.9
     */
    void done() {
        _inProgress.set(false);
        _context.simpleTimer2().addEvent(new StatusCleaner(_lastStatus, _lastError), STATUS_CLEAN_TIME);
    }

    /**
     * Status from current reseed attempt,
     * probably empty if no reseed in progress.
     * May include HTML.
     *
     * @return non-null, may be empty
     * @since 0.9
     */
    public String getStatus() {
        return _lastStatus;
    }

    /**
     * Status from current reseed attempt
     *
     * @param s non-null, may be empty
     * @since 0.9
     */
    void setStatus(String s) {
        _lastStatus = s;
    }

    /**
     * Error from last or current reseed attempt.
     * May include HTML.
     *
     * @return non-null, may be empty
     * @since 0.9
     */
    public String getError() {
        return _lastError;
    }

    /**
     * Status from last or current reseed attempt
     *
     * @param s non-null, may be empty
     * @since 0.9
     */
    void setError(String s) {
        _lastError = s;
    }

    /**
     * @since 0.9.19
     */
    private class StatusCleaner implements SimpleTimer.TimedEvent {
        private final String _status, _error;

        public StatusCleaner(String status, String error) {
            _status = status;
            _error = error;
        }

        public void timeReached() {
            if (_status.equals(getStatus()))
                setStatus("");
            if (_error.equals(getError()))
                setError("");
        }
    }
}
