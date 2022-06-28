package net.i2p.i2ptunnel.udpTunnel;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

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
 * Server side(I2PTunnelUDPServerClient.java):
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

public class I2PTunnelUDPClient extends I2PTunnelUDPClientBase {
    private final Log _log = new Log(I2PTunnelUDPClient.class);

    // UDP Side
    // permanent DatagramSocket at e.g. localhost:5353
    private final DatagramSocket _socket;
    // InetAddress corresponding to local DatagramSocket
    private final InetAddress UDP_HOSTNAME;
    // UDP port corresponding to local DatagramSocket
    private final int UDP_PORT;
    private final int MAX_SIZE = 1024;
    private final UDPSink _sink;

    // SourceIP/Port table
    private Map<Integer, InetSocketAddress> _sourceIPPortTable = new HashMap<>();

    // Constructor, host is localhost(usually) or the host of the UDP client, port
    // is the port of the UDP client
    public I2PTunnelUDPClient(String host, int port, String destination, Logging l, EventDispatcher notifyThis,
            I2PTunnel tunnel) {
        // super(host, port,
        super(destination, l, notifyThis, tunnel);
        UDPSink sink = null;
        UDP_PORT = port;
        InetAddress udpHostname = null;
        try {
            udpHostname = InetAddress.getByName(host);
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed to resolve hostname, using default(localhost): " + host, e);
            udpHostname = null;
        }
        UDP_HOSTNAME = udpHostname;
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(UDP_PORT, UDP_HOSTNAME);
        } catch (Exception e) {
            socket = null;
        }
        _socket = socket;
        if (!_socket.isBound()) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed to bind to UDP port: " + UDP_PORT);
            _socket.close();
        }
        try {
            sink = new UDPSink(socket, InetAddress.getByName(host), port);
        } catch (Exception e) {
            sink = null;
        }
        this._sink = sink;
        // this._source = new UDPSource(this._sink.getSocket());
        this.setSink(this._sink);
    }

    private int newSourcePort() {
        int randomPort = (int) (Math.random() * 65535);
        while (_sourceIPPortTable.containsKey(randomPort)) {
            randomPort = (int) (Math.random() * 65535);
        }
        return randomPort;
    }

    private void sendRepliableI2PDatagram(DatagramPacket packet) {
        try {
            InetSocketAddress sourceIP = new InetSocketAddress(packet.getAddress(), packet.getPort());
            int sourcePort = this.newSourcePort();
            _sourceIPPortTable.put(sourcePort, sourceIP);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Added sourceIP/port to table: " + sourceIP.toString());
            this.send(null, sourcePort, 0, packet.getData());
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed to send UDP packet", e);
        }
    }

    private DatagramPacket recieveRAWReplyPacket() {
        DatagramPacket packet = new DatagramPacket(new byte[MAX_SIZE], MAX_SIZE);
        try {
            this._socket.receive(packet);
        } catch (SocketTimeoutException ste) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Socket timeout, no packet received");
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Failed to receive UDP packet", e);
        }
        return packet;
    }

    private final synchronized void send() {
        while (true) {
            DatagramPacket outboundpacket = new DatagramPacket(new byte[MAX_SIZE], MAX_SIZE);
            try {
                this._socket.receive(outboundpacket);
            } catch (SocketTimeoutException ste) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Socket timeout, no packet received");
                break;
            } catch (Exception e) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Failed to receive UDP packet", e);
                break;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received UDP packet on local address: " + outboundpacket.getAddress().toString()
                        + ", Forwarding");
            this.sendRepliableI2PDatagram(outboundpacket);
        }
    }

    private final synchronized void receive() {
        while (true) {
            DatagramPacket packet = new DatagramPacket(new byte[MAX_SIZE], MAX_SIZE);
            try {
                this._socket.receive(packet);
            } catch (SocketTimeoutException ste) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Socket timeout, no packet received");
                break;
            } catch (Exception e) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Failed to receive UDP packet", e);
                break;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received UDP packet on local address: " + packet.getAddress().toString()
                        + ", Forwarding");
            this.sendRepliableI2PDatagram(packet);
        }
    }

    @Override
    public final void startRunning() {
        super.startRunning();
        while (true) {
            this.send();
            this.receive();
        }
    }

    @Override
    public boolean close(boolean forced) {
        return super.close(forced);
    }
}
