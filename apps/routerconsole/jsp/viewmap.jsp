<%
/*
 * USE CAUTION WHEN EDITING
 * Trailing whitespace OR NEWLINE on the last line will cause
 * IllegalStateExceptions !!!
 *
 * Do not tag this file for translation.
 */
        response.setContentType("image/png");
        response.setHeader("Content-Disposition", "inline; filename=\"i2pmap.png\"");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Accept-Ranges", "none");
        response.setHeader("Connection", "Close");
        java.io.OutputStream cout = response.getOutputStream();
        net.i2p.router.web.helpers.MapMaker mm = new net.i2p.router.web.helpers.MapMaker();
        boolean rendered = mm.render(0, cout);

        if (rendered)
            cout.close();
        else
            response.sendError(403, "Map not available");
%>