package org.klomp.snark.web;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

import org.klomp.snark.Storage;

/**
 * Adds a header, X-I2P-TorrentLocation, to requests for files which contains
 * a magnet link. The magnet link contains:
 * - The infohash of the file to download
 * - At least one open tracker
 * - A web seed corresponding to the URL of the file
 *
 * @since 0.9.51
 */
public class XI2PTorrentLocationFilter extends HandlerWrapper {
    private static final String encodeUTF = StandardCharsets.UTF_8.toString();
    private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(XI2PTorrentLocationFilter.class);

    private synchronized File shouldRecheck(final HttpServletRequest httpRequest) {
        File recheck = null;
        // get the request URI path only
        String path = httpRequest.getRequestURI();
        if (path != null && !path.endsWith("/")) {
            // get the docroot
            String docroot = httpRequest.getContextPath();
            // combine the docroot with the path
            File filepath = new File(docroot, path);
            // exist?
            if (filepath.exists()) {
                // is *not* it a torrent file?
                if (!filepath.getName().endsWith(".torrent")) {
                    // is it a directory?
                    if (!filepath.isDirectory()) {
                        File precheck = filepath.getAbsoluteFile();
                        File torrentcheck = new File(precheck.getParentFile(), precheck.getName() + ".torrent");
                        if (torrentcheck.exists()) {
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

            Storage torrentData;/*
                                 * = new Storage(I2PSnarkUtil util,
                                 * File baseFile,
                                 * String announce,
                                 * List<List<String>> announce_list,
                                 * String created_by,
                                 * boolean privateTorrent,
                                 * List<String> url_list,
                                 * String comment,
                                 * StorageListener listener)
                                 */
        }
        return "";
    }

    @Override
    public void handle(final String target, final Request request, final HttpServletRequest httpRequest,
            HttpServletResponse httpResponse)
            throws IOException, ServletException {

        _handler.handle(target, request, httpRequest, httpResponse);
    }
}
