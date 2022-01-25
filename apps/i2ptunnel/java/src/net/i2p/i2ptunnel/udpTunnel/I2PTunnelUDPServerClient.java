package net.i2p.i2ptunnel.udpTunnel;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
import net.i2p.i2ptunnel.streamr.Pinger;
import net.i2p.i2ptunnel.udp.UDPSink;
import net.i2p.i2ptunnel.udp.UDPSource;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 *
 * * Client side:
 *
 * - permanent DatagramSocket at e.g. localhost:5353
 * - For EVERY incoming IP datagram request, assign a new I2CP source port,
 * store the source IP/port in a table keyed by the I2CP source port
 * - send a REPLIABLE datagram to the server with the I2CP source port
 *
 * Server side:
 *
 * - receive request, store source I2P Dest/port associated with the request
 * - For EVERY incoming I2P datagram request, open a NEW DatagramSocket on
 * localhost with an EPHEMERAL port. Send the request out the socket and wait
 * for a single reply.
 * - Send reply as a RAW datagram to the stored I2P Dest/port. and CLOSE the
 * DatagramSocket.
 *
 * Client side:
 *
 * - receive reply on the destination I2CP port. Look up source IP/port in the
 * table by the destination I2CP port.
 * - Send reply to the stored IP/port, and remove entry from table.
 *
 * @author idk
 */
public class I2PTunnelUDPServerClient extends I2PTunnelUDPServerBase {
    private final Log _log = new Log(I2PTunnelUDPServerClient.class);
    private final UDPSink sink;
    private final UDPSource source;
    private final InetAddress UDP_HOSTNAME;
    private final int UDP_PORT;
    private final int MAX_SIZE = 1024;

    public I2PTunnelUDPServerClient(String host, int port, File privkey, String privkeyname, Logging l,
            EventDispatcher notifyThis,
            I2PTunnel tunnel) {
        super(privkey, privkeyname, l, notifyThis, tunnel);
        // File privkey, String privkeyname, Logging l,EventDispatcher notifyThis,
        // I2PTunnel tunnel
        InetAddress _udpHostname = null;
        try {
            _udpHostname = InetAddress.getByName(host);
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed to resolve hostname, using default(localhost): " + host, e);
            try {
                _udpHostname = InetAddress.getLocalHost();
            } catch (Exception crite) {
                if (_log.shouldLog(Log.ERROR))
                    _log.warn("Failed to resolve localhost, UDP tunnel will fail.: " + host, crite);
                _udpHostname = null;
            }
        } finally {
            if (_udpHostname == null) {
                _log.error("Failed to resolve UDP hostname: " + host);
            }
        }
        this.UDP_HOSTNAME = _udpHostname;
        this.UDP_PORT = port;
        this.sink = new UDPSink(this.UDP_HOSTNAME, this.UDP_PORT);
        this.source = new UDPSource(this.UDP_PORT);
        this.setSink(this.sink);
    }

    private DatagramPacket recieveRepliableDatagramFromClient() {
        byte[] buf = new byte[MAX_SIZE];
        DatagramPacket pack = new DatagramPacket(buf, buf.length);
        try {
            DatagramSocket sock = new DatagramSocket(0);
            sock.receive(pack);
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving UDP datagram from client", e);
            return null;
        }
        return pack;
    }

    @Override
    public final void startRunning() {
        super.startRunning();
        // send subscribe-message
        l.log("I2PTunnelUDPServer server ready");
    }

    @Override
    public boolean close(boolean forced) {
        // send unsubscribe-message
        this.sink.stop();
        return super.close(forced);
    }
}
