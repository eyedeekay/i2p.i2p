package net.i2p.router.web.helpers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.web.ContextHelper;
import net.i2p.router.tunnel.pool.TunnelPool;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounterUnsafe;

/**
 * Generate a transparent image to overlay the world map in a Web Mercator format.
 *
 * Also contains commented-out code to generate the mercator.txt file.
 *
 * @since 0.9.xx
 */
public class MapMaker {
    private final RouterContext _context;
    private final Log _log;

    private static final Map<String, Mercator> _mercator = new HashMap<String, Mercator>(256);

    static {
        readMercatorFile();
    }

    private static final String LATLONG_DEFAULT = "latlong.csv";
    private static final String MERCATOR_DEFAULT = "mercator.txt";
    private static final String BASEMAP_DEFAULT = "mapbase72.png";
    private static final int WIDTH = 1600;
    private static final int HEIGHT = 1600;
    private static final int MAP_HEIGHT = 828;
    // offsets from mercator to image.
    // left side at 171.9 degrees (rotated 36 pixels)
    // tweak to make it line up, eyeball Taiwan
    private static final int IMG_X_OFF = -34;
    // We crop the top from 85 degrees down to about 75 degrees (283 pixels)
    // We crop the bottom from 85 degrees down to about 57 degrees (489 pixels)
    private static final int IMG_Y_OFF = -283;
    // center text on the spot
    private static final int TEXT_Y_OFF = 5;
    private static final Color TEXT_COLOR = new Color(255, 0, 0);
    private static final String FONT_NAME = "Dialog";
    private static final int FONT_STYLE = Font.BOLD;
    private static final int FONT_SIZE = 12;
    private static final Color CIRCLE_BORDER_COLOR = new Color(192, 0, 0, 192);
    private static final Color CIRCLE_COLOR = new Color(160, 0, 0, 128);
    private static final double CIRCLE_SIZE_FACTOR = 4.0;
    private static final int MIN_CIRCLE_SIZE = 5;
    private static final Color SQUARE_BORDER_COLOR = new Color(0, 0, 0);
    private static final Color SQUARE_COLOR = new Color(255, 50, 255, 160);
    private static final Color EXPL_COLOR = new Color(255, 100, 0);
    private static final Color CLIENT_COLOR = new Color(255, 160, 160);
    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    /**
     *
     */
    public MapMaker() {
        this(ContextHelper.getContext(null));
    }

    /**
     *
     */
    public MapMaker(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(MapMaker.class);
    }

/*
    private static class LatLong {
        public final float lat, lon;
        public LatLong(float lat, float lon) {
            this.lat = lat; this.lon = lon;
        }
    }
*/

    private static class Mercator {
        public final int x, y;
        public Mercator(int x, int y) {
            this.x = x; this.y = y;
        }
        @Override
        public int hashCode() {
            return x + y;
        }
        @Override
        public boolean equals(Object o) {
            Mercator m = (Mercator) o;
            return x == m.x && y == m.y;
        }
    }

/*
    private static class DummyImageObserver implements ImageObserver {
        public boolean imageUpdate(Image imgs, int infoflags, int x, int y, int width, int height) { return false; }
    }
*/

    /**
     * @param mode ignored for now, could be different for tunnels or routers or specific subsets
     */
    public boolean render(int mode, OutputStream out) throws IOException {
        if (_mercator.isEmpty()) {
            _log.warn("mercator file not found");
            return false;
        }
/*
        // Putting the map and the overlay in the same image makes it large.
        // Now we load the map separately and overlay the circles and lines with CSS.
        // map source https://github.com/mfeldheim/hermap
        InputStream is = MapMaker.class.getResourceAsStream("/net/i2p/router/web/resources/" + BASEMAP_DEFAULT);
        if (is == null) {
            _log.warn("base map not found");
            return false;
        }
*/

        ObjectCounterUnsafe<String> countries = new ObjectCounterUnsafe<String>();
        for (RouterInfo ri : _context.netDb().getRouters()) {
            Hash key = ri.getIdentity().getHash();
            String country = _context.commSystem().getCountry(key);
            if (country != null)
                countries.increment(country);
        }
        Set<String> counts = countries.objects();

        //BufferedImage bi = ImageIO.read(is);
        //is.close();
        BufferedImage bi = new BufferedImage(WIDTH, MAP_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        //g.drawImage(base, 0, 0, new DummyImageObserver());
        Font large = new Font(FONT_NAME, FONT_STYLE, FONT_SIZE);
        g.setFont(large);
        g.setBackground(TRANSPARENT);
        g.setPaint(TEXT_COLOR);
        g.setStroke(new BasicStroke(1));

        for (String c : countries.objects()) {
            Mercator m = _mercator.get(c);
            if (m == null)
                continue;
            int count = countries.count(c);
            int sz = Math.max(MIN_CIRCLE_SIZE, (int) (CIRCLE_SIZE_FACTOR * Math.sqrt(count)));
            drawCircle(g, rotate(m.x), m.y + IMG_Y_OFF, sz);
            c = c.toUpperCase(Locale.US);
            double width = getStringWidth(c, large, g);
            int xoff = (int) (width / 2);
            g.drawString(c.toUpperCase(Locale.US), rotate(m.x) - xoff, m.y + IMG_Y_OFF + TEXT_Y_OFF);
        }

        String us = _context.commSystem().getOurCountry();
        if (us != null) {
            Mercator mus = _mercator.get(us);
            if (mus != null) {
                drawSquare(g, rotate(mus.x), mus.y + IMG_Y_OFF, 24);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(2));
                TunnelManagerFacade tm = _context.tunnelManager();
                renderPool(g, mus, tm.getInboundExploratoryPool(), EXPL_COLOR);
                renderPool(g, mus, tm.getOutboundExploratoryPool(), EXPL_COLOR);
                Map<Hash, TunnelPool> pools = tm.getInboundClientPools();
                // TODO skip aliases
                for (TunnelPool tp : pools.values()) {
                    renderPool(g, mus, tp, CLIENT_COLOR);
                }
                pools = tm.getOutboundClientPools();
                for (TunnelPool tp : pools.values()) {
                    renderPool(g, mus, tp, CLIENT_COLOR);
                }
            }
        }

        ImageOutputStream ios = new MemoryCacheImageOutputStream(out);
        ImageIO.write(bi, "png", ios);
        return true;
    }

   /**
    * Draw circle centered on x,y with a radius given
    */
    private void drawCircle(Graphics2D g, int x, int y, int radius) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color c = g.getColor();
        g.setColor(CIRCLE_BORDER_COLOR);
        g.drawArc(x - radius, y - radius, radius * 2, radius * 2, 0, 360);
        g.setColor(CIRCLE_COLOR);
        g.fillArc(x - radius, y - radius, radius * 2, radius * 2, 0, 360);
        g.setColor(c);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    }

   /**
    * Draw square centered on x,y with a width/height given
    */
    private void drawSquare(Graphics2D g, int x, int y, int sz) {
        Color c = g.getColor();
        g.setColor(SQUARE_BORDER_COLOR);
        g.drawRect(x - (sz/2), y - (sz/2), sz, sz);
        g.setColor(SQUARE_COLOR);
        g.fillRect(x - (sz/2), y - (sz/2), sz, sz);
        g.setColor(c);
    }

    private void renderPool(Graphics2D g, Mercator mus, TunnelPool tp, Color color) {
        Color c = g.getColor();
        g.setColor(color);
        List<TunnelInfo> tunnels = tp.listTunnels();
        List<Mercator> hops = new ArrayList<Mercator>(8);
        int[] x = new int[8];
        int[] y = new int[8];
        for (TunnelInfo info : tunnels) {
            int length = info.getLength();
            if (length < 2)
                continue;
            boolean isInbound = info.isInbound();
            // gateway first
            for (int j = 0; j < length; j++) {
                Mercator m;
                if (isInbound && j == length - 1) {
                    m = mus;
                } else if (!isInbound && j == 0) {
                    m = mus;
                } else {
                    Hash peer = info.getPeer(j);
                    String country = _context.commSystem().getCountry(peer);
                    if (country == null)
                        continue;
                    Mercator mc = _mercator.get(country);
                    if (mc == null)
                        continue;
                    m = mc;
                }
                if (hops.isEmpty() || !m.equals(hops.get(hops.size() - 1))) {
                    hops.add(m);
                }
            }
            int sz = hops.size();
            if (sz > 1) {
                for (int i = 0; i < sz; i++) {
                    Mercator m = hops.get(i);
                    x[i] = rotate(m.x);
                    y[i] = m.y + IMG_Y_OFF;
                }
                g.drawPolyline(x, y, sz);
            }
            hops.clear();
        }
        g.setColor(c);
    }

    private static double getStringWidth(String text, Font font, Graphics2D g) {
        return font.getStringBounds(text, 0, text.length(), g.getFontRenderContext()).getBounds().getWidth();
    }

    private static int rotate(int x) {
        x += IMG_X_OFF;
        if (x < 0)
            x += WIDTH;
        return x;
    }

   /**
    * Read in and parse the mercator country file.
    * The file need not be sorted.
    * This file was created from the lat/long data at
    * https://developers.google.com/public-data/docs/canonical/countries_csv
    * using the convertLatLongFile() method below.
    */
    private static void readMercatorFile() {
        InputStream is = MapMaker.class.getResourceAsStream("/net/i2p/router/web/resources/" + MERCATOR_DEFAULT);
        if (is == null) {
            System.out.println("Country file not found");
            return;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    if (line.charAt(0) == '#')
                        continue;
                    String[] s = DataHelper.split(line, ",", 3);
                    if (s.length < 3)
                        continue;
                    int x = Integer.parseInt(s[1]);
                    int y = Integer.parseInt(s[2]);
                    _mercator.put(s[0], new Mercator(x, y));
                } catch (NumberFormatException nfe) {
                    System.out.println("Bad line " + nfe);
                }
            }
        } catch (IOException ioe) {
            System.out.println("Error reading the Country File " + ioe);
        } finally {
            if (is != null) try { is.close(); } catch (IOException ioe) {}
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
    }

   /**
    * Read in and parse the lat/long file.
    * The file need not be sorted.
    * Convert the lat/long data from
    * https://developers.google.com/public-data/docs/canonical/countries_csv
    * to a 1200x1200 web mercator (85 degree) format.
    * latlong.csv input format: XX,lat,long,countryname (lat and long are signed floats)
    * mercator.txt output format: xx,x,y (x and y are integers 0-1200, not adjusted for a cropped projection)
    * Output is sorted by country code.
    */
/****
    private static void convertLatLongFile() {
        Map<String, LatLong> latlong = new HashMap<String, LatLong>();
        InputStream is = null;
        BufferedReader br = null;
        try {
            is = new FileInputStream(LATLONG_DEFAULT);
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line = null;
            while ( (line = br.readLine()) != null) {
                try {
                    if (line.charAt(0) == '#')
                        continue;
                    String[] s = DataHelper.split(line, ",", 4);
                    if (s.length < 3)
                        continue;
                    String lc = s[0].toLowerCase(Locale.US);
                    float lat = Float.parseFloat(s[1]);
                    float lon = Float.parseFloat(s[2]);
                    latlong.put(lc, new LatLong(lat, lon));
                } catch (NumberFormatException nfe) {
                    System.out.println("Bad line " + nfe);
                }
            }
        } catch (IOException ioe) {
            System.out.println("Error reading the Country File " + ioe);
        } finally {
            if (is != null) try { is.close(); } catch (IOException ioe) {}
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
        Map<String, Mercator> mercator = new TreeMap<String, Mercator>();
        for (Map.Entry<String, LatLong> e : latlong.entrySet()) {
            String c = e.getKey();
            LatLong ll = e.getValue();
            mercator.put(c, convert(ll));
        }
        for (Map.Entry<String, Mercator> e : mercator.entrySet()) {
            String c = e.getKey();
            Mercator m = e.getValue();
            System.out.println(c + ',' + m.x + ',' + m.y);
        }
    }
****/

    /**
     *  https://stackoverflow.com/questions/57322997/convert-geolocation-to-pixels-on-a-mercator-projection-image
     */
/****
    private static Mercator convert(LatLong latlong) {
        double rad = latlong.lat * Math.PI / 180;
        double mercn = Math.log(Math.tan((Math.PI / 4) + (rad / 2)));
        double x = (latlong.lon + 180d) * (WIDTH / 360d);
        double y = (HEIGHT / 2d) - ((WIDTH * mercn) / (2 * Math.PI));
        return new Mercator((int) Math.round(x), (int) Math.round(y));
    }

    public static void main(String args[]) {
        convertLatLongFile();
    }
****/
}
