package org.klomp.snark.web;

import java.io.FileWriter;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

import org.klomp.snark.Storage;
//import org.klomp.snark.Snark;
//import org.klomp.snark.SnarkManager;
import org.klomp.snark.I2PSnarkUtil;
import org.klomp.snark.MetaInfo;
import org.eclipse.jetty.server.Handler;

/**
 * Adds a header, X-I2P-TorrentLocation, to requests for files which contains
 * a magnet link. The magnet link contains:
 * - The infohash of the file to download
 * - At least one open tracker
 * - A web seed corresponding to the URL of the file
 *
 * @since 0.9.51
 */
public class XI2PTorrentLocationFilter extends HandlerWrapper implements Handler {
    private static final String encodeUTF = StandardCharsets.UTF_8.toString();
    private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(XI2PTorrentLocationFilter.class);
    private final I2PSnarkUtil _util;

    public XI2PTorrentLocationFilter() {
        // get the I2PAppContext
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        _util = new I2PSnarkUtil(ctx);
    }

    public XI2PTorrentLocationFilter(I2PSnarkUtil util) {
        _util = util;
    }

    private synchronized File shouldRecheck(final HttpServletRequest httpRequest) {
        File recheck = null;
        if (_log.shouldLog(_log.DEBUG)) {
            _log.debug("shouldRecheck: " + httpRequest.getRequestURI());
        }
        // get the request URI path only
        String path = httpRequest.getRequestURI();
        if (path != null && !path.endsWith("/")) {
            // get the docroot
            String docroot = httpRequest.getContextPath();
            // combine the docroot with the path
            File filepath = new File(docroot, path);
            // exist?
            if (filepath.exists()) {
                if (_log.shouldLog(_log.DEBUG)) {
                    _log.debug("shouldRecheck: exists");
                }
                // is *not* it a torrent file?
                if (!filepath.getName().endsWith(".torrent")) {
                    if (_log.shouldLog(_log.DEBUG)) {
                        _log.debug("shouldRecheck: not a torrent file");
                    }
                    // is it a directory?
                    if (!filepath.isDirectory()) {
                        if (_log.shouldLog(_log.DEBUG)) {
                            _log.debug("shouldRecheck: not a directory");
                        }
                        File precheck = filepath.getAbsoluteFile();
                        File torrentcheck = new File(precheck.getParentFile(), precheck.getName() + ".torrent");
                        if (torrentcheck.exists()) {
                            if (_log.shouldLog(_log.DEBUG)) {
                                _log.debug("shouldRecheck: torrent exists");
                            }
                            // get the last modified fime of precheck
                            long lastModified = precheck.lastModified();
                            // get the last modified time of torrentcheck
                            long lastModified2 = torrentcheck.lastModified();
                            // if the last modified time of torrentcheck is less than the last modified
                            // time of precheck
                            if (lastModified2 < lastModified) {
                                // set the recheck to the precheck
                                recheck = precheck;
                            }
                        } else {
                            if (_log.shouldLog(_log.DEBUG)) {
                                _log.debug("shouldRecheck: torrent does not exist");
                            }
                            // set the recheck to the precheck
                            recheck = precheck;
                        }
                    }
                }
            }
        }
        return recheck;
    }

    private synchronized String headerContents(final HttpServletRequest httpRequest) {
        File recheck = shouldRecheck(httpRequest);
        if (recheck != null) {
            // return null;
            List<String> openTrackers = _util.getOpenTrackers();
            List<List<String>> announce_list = null;
            if (openTrackers != null) {
                if (_log.shouldLog(_log.DEBUG)) {
                    _log.debug("headerContents: has opentrackers");
                }
                if (openTrackers.size() > 1) {
                    announce_list = new ArrayList<List<String>>();
                    // for (String tracker : openTrackers) {
                    for (int i = 1; i < openTrackers.size(); i++) {
                        String tracker = openTrackers.get(i);
                        List<String> announce = new ArrayList<String>();
                        announce.add(tracker);
                        announce_list.add(announce);
                    }
                }
                String announce = openTrackers.get(0);
                List<String> url_list = new ArrayList<String>();
                String url = httpRequest.getRequestURL().toString();
                url_list.add(url);
                try {
                    Storage torrentData = new Storage(_util,
                            recheck,
                            announce,
                            announce_list,
                            null,
                            false,
                            url_list,
                            null,
                            null);
                    String torrentString = torrentData.toString();
                    MetaInfo metaInfo = torrentData.getMetaInfo();
                    String magnet = "magnet:?xt=urn:btih:" + metaInfo.getInfoHash() +
                            "&dn=" + metaInfo.getName() +
                            "&tr=" + announce +
                            "&ws=" + url;
                    // write torrentString to file recheck.torrent
                    File torrentFile = new File(recheck.getParentFile(), recheck.getName() + ".torrent");
                    if (torrentFile.exists()) {
                        torrentFile.delete();
                    }
                    BufferedWriter writer = new BufferedWriter(new FileWriter(torrentFile));
                    writer.write(torrentString);
                    writer.close();
                    torrentData.close();
                    return magnet;

                } catch (IOException e) {
                    _log.error("Error creating torrent", e);
                }
            }
        }
        if (_log.shouldLog(_log.DEBUG)) {
            _log.debug("headerContents: unable to create torrent");
        }
        return "";
    }

    @Override
    public void handle(final String target, final Request request, final HttpServletRequest httpRequest,
            HttpServletResponse httpResponse)
            throws IOException, ServletException {

        String header = headerContents(httpRequest);
        if (header != null && !header.equals("")) {
            httpResponse.setHeader("X-I2P-TorrentLocation", header);
        }

        _handler.handle(target, request, httpRequest, httpResponse);
    }
}
