package net.i2p.jetty;

import java.io.IOException;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKeyFile;


import net.i2p.util.Log;

/**
 *  @since 0.9.50
 */
public class XI2PTorrentLocationFilter extends HandlerWrapper {
    private String X_I2P_TORRENTLOCATION = null;
    Log _log = I2PAppContext.getGlobalContext().logManager().getLog(XI2PTorrentLocationFilter.class);


    private synchronized void setLocation(String xi2ptorrentlocation) {
        if (_log.shouldInfo())
            _log.info("Checking X-I2P-TORRENTLOCATION header prefix" + xi2ptorrentlocation);
        if (X_I2P_TORRENTLOCATION != null)
            return ;
        if (xi2ptorrentlocation == null)
            return ;
        if (xi2ptorrentlocation.equals(""))
            return ;
        X_I2P_TORRENTLOCATION = xi2ptorrentlocation;
        if (_log.shouldInfo())
            _log.info("Caching X-I2P-TORRENTLOCATION header prefix" + X_I2P_TORRENTLOCATION);
    }

    public synchronized String getXI2PLocation(String host, String port) {
        File configDir = I2PAppContext.getGlobalContext().getConfigDir();
        File tunnelConfig = new File(configDir, "i2ptunnel.config");
        boolean isSingleFile = tunnelConfig.exists();
        if (!isSingleFile) {
            File tunnelConfigD = new File(configDir, "i2ptunnel.config.d");
            File[] configFiles = tunnelConfigD.listFiles(new net.i2p.util.FileSuffixFilter(".config"));
            if (configFiles == null)
                return null;
            for (int fnum=0; fnum < configFiles.length; fnum++) {
                Properties tunnelProps = new Properties();
                try {
                    DataHelper.loadProps(tunnelProps, configFiles[fnum]);
// TODO ::, 0.0.0.0, hostnames...
                    if (host.equals(tunnelProps.getProperty("targetHost")) && port.equals(tunnelProps.getProperty("targetPort")) ) {
                        String kf = tunnelProps.getProperty("privKeyFile");
                        if (kf != null) {
                            File keyFile = new File(kf);
                            if (!keyFile.isAbsolute())
                                keyFile = new File(configDir, kf);
                            if (keyFile.exists()) {
                                PrivateKeyFile pkf = new PrivateKeyFile(keyFile);
                                try {
                                    Destination rv = pkf.getDestination();
                                    if (rv != null)
                                        return rv.toBase32();
                                } catch (I2PException e) {
                                    if (_log.shouldWarn())
                                        _log.warn("I2PException Unable to set X-I2P-TORRENTLOCATION, keys arent ready. This is probably safe to ignore and will go away after the first run." + e);
                                    return null;
                                } catch (IOException e) {
                                    if (_log.shouldWarn())
                                        _log.warn("IOE Unable to set X-I2P-TORRENTLOCATION, location is uninitialized due file not found. This probably means the keys aren't ready. This is probably safe to ignore." + e);
                                    return null;
                                }
                            }
                        }
                        if (_log.shouldWarn())
                            _log.warn("Unable to set X-I2P-TORRENTLOCATION, location is target not found in any I2PTunnel config file. This should never happen.");
                        return null;
                    }
                } catch (IOException ioe) {
                    if (_log.shouldWarn())
                        _log.warn("IOE Unable to set X-I2P-TORRENTLOCATION, location is uninitialized. This is probably safe to ignore. location='" + ioe + "'");
                    return null;
                }
            }
        } else {
            // don't bother
        }
        return null;
    }

    @Override
    public void handle(final String target, final Request request, final HttpServletRequest httpRequest, HttpServletResponse httpResponse)
    throws IOException,
        ServletException {

// FIXME sync
        if (X_I2P_TORRENTLOCATION == null) {
            String xi2ptorrentlocation = getXI2PLocation(request.getLocalAddr(), String.valueOf(request.getLocalPort()));
            if (_log.shouldInfo())
                _log.info("Checking X-I2P-TORRENTLOCATION header IP " + request.getLocalAddr() + " port " + request.getLocalPort() + " prefix " + xi2ptorrentlocation);
            setLocation(xi2ptorrentlocation);
        }

        if (X_I2P_TORRENTLOCATION != null) {
            if (_log.shouldInfo())
                _log.info("Checking X-I2P-TORRENTLOCATION header prefix" + X_I2P_TORRENTLOCATION);
            final String requestUri = httpRequest.getRequestURL().toString();
            URL url = new URL(requestUri);
            String domain = url.getHost();
            if (domain != null) {
                if (!domain.endsWith(".i2p")) {
                    String query = url.getQuery();
                    if (query == null)
                        query = "";
                    httpResponse.addHeader("X-I2P-TORRENTLOCATION", X_I2P_TORRENTLOCATION+url.getPath()+query);
                }
            }
        }

        _handler.handle(target, request, httpRequest, httpResponse);
    }
}
