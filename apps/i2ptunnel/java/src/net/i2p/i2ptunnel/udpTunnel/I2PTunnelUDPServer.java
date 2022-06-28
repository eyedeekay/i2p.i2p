package net.i2p.i2ptunnel.udpTunnel;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.i2p.client.I2PSession;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.data.DataFormatException;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.udp.I2PSink;
import net.i2p.i2ptunnel.udp.I2PSource;
//import net.i2p.i2ptunnel.streamr.Pinger;
import net.i2p.i2ptunnel.udp.UDPSink;
import net.i2p.i2ptunnel.udp.UDPSource;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 *
 * Client side(I2PTunnelUDPClient.java):
 *
 * - permanent DatagramSocket at e.g. localhost:5353
 * - For EVERY incoming IP datagram request, assign a new I2CP source port,
 * store the source IP/port in a table keyed by the I2CP source port
 * - send a REPLIABLE datagram to the server with the I2CP source port
 *
 * Server side(I2PTunnelUDPServer.java):
 *
 * - receive request, store source I2P Dest/port associated with the request
 * - For EVERY incoming I2P datagram request, open a NEW DatagramSocket on
 * localhost with an EPHEMERAL port. Send the request out the socket and wait
 * for a single reply.
 * - Send reply as a RAW datagram to the stored I2P Dest/port. and CLOSE the
 * DatagramSocket.
 *
 * Client side(I2PTunnelUDPClient.java):
 *
 * - receive reply on the destination I2CP port. Look up source IP/port in the
 * table by the destination I2CP port.
 * - Send reply to the stored IP/port, and remove entry from table.
 *
 * @author idk
 */

public class I2PTunnelUDPServer extends I2PTunnelUDPServerBase {
    private final Log _log = new Log(I2PTunnelUDPServer.class);

    // UDP Side
    // permanent DatagramSocket at e.g. localhost:5353
    // InetAddress corresponding to local DatagramSocket
    // UDP port corresponding to local DatagramSocket
    // SourceIP/Port table
    private Map<Integer, InetSocketAddress> _sourceIPPortTable = new HashMap<>();

    // Constructor, host is localhost(usually) or the host of the UDP client, port
    // is the port of the UDP client
    public I2PTunnelUDPServer(String host, int port, File privKeyFile, String privKeyPath, Logging l, EventDispatcher notifyThis,
            I2PTunnel tunnel) {
            //Properties props = tunnel.getClientOptions();
            //String privKeyPath = props.getProperty("privKeyFile");
            //getString("privKeyFile")
            //File privKeyFile = new File(privKeyPath);
            super(privKeyFile, privKeyPath, l, notifyThis, tunnel);

    }

    private int newSourcePort() {
        int randomPort = (int) (Math.random() * 65535);
        while (_sourceIPPortTable.containsKey(randomPort)) {
            randomPort = (int) (Math.random() * 65535);
        }
        return randomPort;
    }

    @Override
    public final void startRunning() {
        super.startRunning();
        while (true) {
        }
    }

    @Override
    public boolean close(boolean forced) {
        return super.close(forced);
    }
}
