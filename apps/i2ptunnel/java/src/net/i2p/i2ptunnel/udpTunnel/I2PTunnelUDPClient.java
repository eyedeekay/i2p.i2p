package net.i2p.i2ptunnel.udpTunnel;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

import net.i2p.client.streaming.I2PSocketAddress;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.I2PTunnel;
import net.i2p.i2ptunnel.Logging;
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
    private final static int MAX_DATAGRAM_SIZE = 31744;
    private final Destination _remoteDestination;

    // UDP Side
    // permanent DatagramSocket at e.g. localhost:5353
    private final DatagramSocket _localSocket;
    // InetAddress corresponding to local DatagramSocket
    private final InetAddress _localAddress;
    // UDP port corresponding to local DatagramSocket
    private final int _localPort;
    //private final int UDP_PORT;
    //private final int MAX_SIZE = 1024;
    private final UDPSink _sink;
    private final UDPSource _source;

    // SourceIP/Port table
    private Map<Integer, InetSocketAddress> _sourceIPPortTable = new HashMap<>();
    private class outboundRequest {
        public final InetAddress _sourceAddress;
        public final int _i2cpSourcePort;
        public final byte[] _request;
        public int length() {
            return _request.length;
        }
        public int sendRequestToI2PServer() {
            try {
                //DatagramPacket packet = new DatagramPacket(_request, _request.length, _serverAddress, _serverPort);
                //send(packet);

                send(_remoteDestination, _i2cpSourcePort, 0, _request);
                return _i2cpSourcePort;
            } catch (Exception e) {
                _log.error("Error sending request to I2PServer", e);
            }
            return 0;
        }
        public outboundRequest(InetAddress sourceAddress, int sourcePort, byte[] request) {
            _sourceAddress = sourceAddress;
            _i2cpSourcePort = sourcePort;
            _request = request;
        }
    }

    // Constructor, host is localhost(usually) or the host of the UDP client, port
    // is the port of the UDP client
    public I2PTunnelUDPClient(String localhost, int localport, String destination, Logging l, EventDispatcher notifyThis,
            I2PTunnel tunnel) {
        super(destination, l, notifyThis, tunnel);
        _log.debug("I2PTunnelUDPClient: " + localhost + ":" + localport + " -> " + destination);
        try {
            this._localAddress = InetAddress.getByName(localhost);
            this._localPort = localport;
        } catch (Exception e) {
            _log.error("I2PTunnelUDPClient: " + e.getMessage());
            throw new RuntimeException(e);
        }
        try {
            this._localSocket = new DatagramSocket(new InetSocketAddress(_localAddress, _localPort));
        } catch (Exception e) {
            _log.error("I2PTunnelUDPClient: " + e.getMessage());
            throw new RuntimeException(e);
        }
        try {
            this._remoteDestination = new Destination(destination);
        } catch (Exception e) {
            _log.error("I2PTunnelUDPClient: " + e.getMessage());
            throw new RuntimeException(e);
        }
        _sink = new UDPSink(this._localSocket, this._localAddress, this._localPort);
        //_localSocket, _remoteDestination, _log, notifyThis, tunnel);
        //_sink.start();
        this._source = new UDPSource(this._localSocket);
        this.setSink(this._sink);
    }

    private DatagramPacket readDatagramFromLocalSocket() {
        byte[] buf = new byte[MAX_DATAGRAM_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
            _localSocket.receive(packet);
        } catch (Exception e) {
            _log.error("I2PTunnelUDPClient: " + e.getMessage());
            throw new RuntimeException(e);
        }
        return packet;
    }

    private outboundRequest readOutboundRequestFromLocalDatagram(){
        DatagramPacket packet = readDatagramFromLocalSocket();
        InetAddress sourceAddress = packet.getAddress();
        int sourcePort = newI2CPSourcePort();
        byte[] request = packet.getData();
        return new outboundRequest(sourceAddress, sourcePort, request);
    }

    private int sendOutboundRequest(){
        outboundRequest request = readOutboundRequestFromLocalDatagram();
        int i2cpSourcePort = request.sendRequestToI2PServer();
        _sourceIPPortTable.put(i2cpSourcePort, new InetSocketAddress(_localSocket.getInetAddress(), _localSocket.getPort()));
        return request.sendRequestToI2PServer();
    }

    private int newI2CPSourcePort() {
        int randomPort = (int) (Math.random() * 55535);
        while (_sourceIPPortTable.containsKey(randomPort)) {
            randomPort = (int) (Math.random() * 55535);
        }
        return randomPort+10000;
    }

    private void recieveInboundResponse() {

    }

    @Override
    public final void startRunning() {
        super.startRunning();
        while (true) {
            int i2cpPortSent = this.sendOutboundRequest();
            _log.debug("I2PTunnelUDPClient: sent outbound request to I2PServer, i2cpPortSent: " + i2cpPortSent);
            //this.receive();
        }
    }

    @Override
    public boolean close(boolean forced) {
        return super.close(forced);
    }
}
