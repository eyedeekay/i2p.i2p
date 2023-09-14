package net.i2p.router.networkdb.kademlia;

import java.io.IOException;
import java.io.Writer;
//import java.rmi.dgc.Lease;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.i2p.data.BlindData;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.TunnelId;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.reseed.ReseedChecker;
import net.i2p.util.Log;

/**
 * FloodfillNetworkDatabaseSegmentor
 * 
 * Default implementation of the SegmentedNetworkDatabaseFacade.
 * 
 * This is a datastructure which manages (3+Clients) "sub-netDbs" on behalf of an
 * I2P router, each representing it's own view of the network. Normally, these sub-netDb's
 * are identified by the hash of the primary session belonging to the client who "owns"
 * a particular sub-netDb.
 * 
 * There are 3 "Special" netDbs which have non-hash names:
 * 
 *  - Main NetDB: This is the netDb we use if or when we become a floodfill, and for
 *  direct interaction with other routers on the network, such as when we are communicating
 *  with a floodfill.
 *  - Multihome NetDB: This is used to stash leaseSets for our own sites when they are
 *  sent to us by a floodfill, so that we can reply when they are requested back from us
 *  regardless of our closeness to them in the routing table.
 *  - Exploratory NetDB: This is used when we want to stash a DatabaseEntry for a key
 *  during exploration but don't want it to go into the Main NetDB until we do something
 *  else with it.
 * 
 * And there are an unlimited number of "Client" netDbs. These sub-netDbs are
 * intended to contain only the information required to operate them, and as such
 * most of them are very small, containing only a few LeaseSets belonging to clients.
 * Each one corresponds to a Destination which can recieve information from the
 * netDb, and can be indexed either by it's hash or by it's base32 address. This index
 * is known as the 'dbid' or database id.
 * 
 * Users of this class should strive to always access their sub-netDbs via the
 * explicit DBID of the destination recipient, or using the DBID of the special
 * netDb when it's appropriate to route the netDb entry to one of the special tables.
 * Any usage of the getter functions in this class(FNDF) should be limited to
 * displaying information in the local user interface, router console, etc.
 * 
 * @author idk
 * @since 0.9.60
 */
public class FloodfillNetworkDatabaseSegmentor extends SegmentedNetworkDatabaseFacade {
    protected final Log _log;
    private RouterContext _context;
    private Map<String, FloodfillNetworkDatabaseFacade> _subDBs = new HashMap<String, FloodfillNetworkDatabaseFacade>();
    public static final String MAIN_DBID = "main";
    private static final String MULTIHOME_DBID = "multihome";
    private static final String EXPLORATORY_DBID = "exploratory";
    private final FloodfillNetworkDatabaseFacade _mainDbid;
    private final FloodfillNetworkDatabaseFacade _multihomeDbid;

    public FloodfillNetworkDatabaseSegmentor(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(getClass());
        if (_context == null)
            _context = context;
        _mainDbid = new FloodfillNetworkDatabaseFacade(_context, MAIN_DBID);
        _multihomeDbid = new FloodfillNetworkDatabaseFacade(_context, MULTIHOME_DBID);
    }

    @Override
    public FloodfillNetworkDatabaseFacade createSubNetDB(Hash id) {
        if (id == null)
            return createSubNetDB(MAIN_DBID);
        return createSubNetDB(id.toBase32());
    }

    @Override
    protected FloodfillNetworkDatabaseFacade createSubNetDB(String id) {
        if (id == null || id.isEmpty() || id.equals(MAIN_DBID))
            return mainNetDB();
        if (id.equals(MULTIHOME_DBID))
            return multiHomeNetDB();
        if (id.equals(EXPLORATORY_DBID))
            return clientNetDB();
        Hash myHash = _context.routerHash();
        if (id.equals(myHash.toBase32()))
            return mainNetDB();


        id = clientDbidString(id);

        FloodfillNetworkDatabaseFacade subdb = _subDBs.get(id);
        if (subdb == null) {
            subdb = new FloodfillNetworkDatabaseFacade(_context, id);
            _subDBs.put(id, subdb);
            subdb.startup();
            subdb.createHandlers();
            if (subdb.getFloodfillPeers().size() == 0) {
                List<RouterInfo> ris = mainNetDB().pickRandomFloodfillPeers();
                for (RouterInfo ri : ris) {
                    if (_log.shouldLog(_log.DEBUG))
                        _log.debug("Seeding: " + id + " with " + ris.size() + " peers " + ri.getHash());
                    subdb.store(ri.getIdentity().getHash(), ri);
                }
            }
        }
        return subdb;
    }

    private String clientDbidString(String id) {
        if (id.endsWith(".i2p")) {
            if (!id.startsWith("clients_"))
                id = "clients_" + id;
        }
        return id;
    }

    @Override
    public FloodfillNetworkDatabaseFacade getSubNetDB(Hash dbid) {
        if (dbid == null)
            return null;
        String id = dbid.toBase32();
        return getSubNetDB(id);
    }

    @Override
    public FloodfillNetworkDatabaseFacade getSubNetDB(String id) {
        if (id == null || id.isEmpty() || id.equals(MAIN_DBID))
            return mainNetDB();
        if (id.equals(MULTIHOME_DBID))
            return multiHomeNetDB();
        if (id.equals(EXPLORATORY_DBID))
            return clientNetDB();
        Hash myHash = _context.routerHash();
        if (id.equals(myHash.toBase32()))
            return mainNetDB();

        id = clientDbidString(id);
        return _subDBs.get(id);
    }

    /**
     * If we are floodfill, turn it off and tell everybody.
     * 
     * @since 0.8.9
     */
    public synchronized void shutdown() {
        // shut down every entry in _subDBs
        for (FloodfillNetworkDatabaseFacade subdb : getSubNetDBs()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("(dbid: " + subdb._dbid
                        + ") Shutting down all remaining sub-netDbs",
                        new Exception());
            subdb.shutdown();
        }
    }

    public List<RouterInfo> getKnownRouterData() {
        List<RouterInfo> rv = new ArrayList<RouterInfo>();
        for (FloodfillNetworkDatabaseFacade subdb : getSubNetDBs()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("(dbid: " + subdb._dbid
                        + ") Deprecated! Arbitrary selection of this subDb",
                        new Exception());
            rv.addAll(subdb.getKnownRouterData());
        }
        return rv;
    }

    /**
     * list of the Hashes of currently known floodfill peers;
     * Returned list will not include our own hash.
     * List is not sorted and not shuffled.
     */
    public List<Hash> getFloodfillPeers() {
        List<Hash> peers = new ArrayList<Hash>();
        for (FloodfillNetworkDatabaseFacade subdb : getSubNetDBs()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("(dbid: " + subdb._dbid
                        + ") Deprecated! Arbitrary selection of this subDb",
                        new Exception());
            peers.addAll(subdb.getFloodfillPeers());
        }
        return peers;
    }

    /**
     * Lookup using the client's tunnels when the client LS key is know
     * but the client dbid is not.
     *
     * @param key The LS key for client.
     * @since 0.9.60
     */
    @Override
    public LeaseSet lookupLeaseSetHashIsClient(Hash key) {
        String dbid = matchDbid(key);
        return lookupLeaseSetLocally(key, dbid);
    }

    @Override
    protected LeaseSet lookupLeaseSetLocally(Hash key, String dbid) {
        if (dbid == null || dbid.isEmpty()) {
            LeaseSet rv = null;
            for (FloodfillNetworkDatabaseFacade subdb : getSubNetDBs()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("(dbid: " + subdb._dbid
                            + ") Deprecated! Arbitrary selection of this subDb",
                            new Exception());
                rv = subdb.lookupLeaseSetLocally(key);
                if (rv != null) {
                    return rv;
                }
            }
            rv = this.lookupLeaseSetLocally(key, MAIN_DBID);
            if (rv != null) {
                return rv;
            }
        }
        return this.getSubNetDB(dbid).lookupLeaseSetLocally(key);
    }

    public boolean isInitialized() {
        boolean rv = false;
        for (FloodfillNetworkDatabaseFacade subdb : getSubNetDBs()) {
            rv = subdb.isInitialized();
            if (!rv) {
                break;
            }
        }
        return rv;
    }

    @Override
    public Set<RouterInfo> getRouters() {
        Set<RouterInfo> rv = new HashSet<>();
        for (FloodfillNetworkDatabaseFacade subdb : getSubNetDBs()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("(dbid: " + subdb._dbid
                        + ") Deprecated! Arbitrary selection of this subDb",
                        new Exception());
            rv.addAll(subdb.getRouters());
        }
        return rv;
    }

    public Set<RouterInfo> getRoutersKnownToClients() {
        Set<RouterInfo> rv = new HashSet<>();
        for (String key : getClients()) {
            rv.addAll(this.getSubNetDB(key).getRouters());
        }
        return rv;
    }

    public Set<LeaseSet> getLeasesKnownToClients() {
        Set<LeaseSet> rv = new HashSet<>();
        for (String key : getClients()) {
            rv.addAll(this.getSubNetDB(key).getLeases());
        }
        return rv;
    }

    public List<String> getClients() {
        List<String> rv = new ArrayList<String>();
        for (String key : _subDBs.keySet()) {
            if (key != null && !key.isEmpty()) {
                if (key.startsWith("client"))
                    rv.add(key);
            }
        }
        return rv;
    }

    @Override
    public FloodfillNetworkDatabaseFacade mainNetDB() {
        return _mainDbid;
    }

    @Override
    public FloodfillNetworkDatabaseFacade multiHomeNetDB() {
        return _multihomeDbid;
    }

    @Override
    public FloodfillNetworkDatabaseFacade clientNetDB(String id) {
        if (id == null || id.isEmpty())
            return null;
        return this.getSubNetDB(id);
    }

    @Override
    public FloodfillNetworkDatabaseFacade clientNetDB(Hash id) {
        if (id != null)
            return getSubNetDB(id.toBase32());
        return null;
    }

    public FloodfillNetworkDatabaseFacade clientNetDB() {
        return null;
    }

    @Override
    public List<String> lookupClientBySigningPublicKey(SigningPublicKey spk) {
        List<String> rv = new ArrayList<>();
        for (String subdb : getClients()) {
            // if (subdb.startsWith("clients_"))
            // TODO: see if we can access only one subDb at a time when we need
            // to look up a client by SPK. We mostly need this for managing blinded
            // and encrypted keys in the Keyring Config UI page. See also
            // ConfigKeyringHelper
            BlindData bd = _subDBs.get(subdb).getBlindData(spk);
            if (bd != null) {
                rv.add(subdb);
            }
        }
        return rv;
    }

    /**
     * Public helper to return the dbid that is associated with the
     * supplied client key.
     *
     * @param clientKey The LS key of the subDb context
     * @since 0.9.60
     */
    @Override
    public String getDbidByHash(Hash clientKey) {
        return matchDbid(clientKey);
    }

    /**
     * Return the dbid that is associated with the supplied client LS key
     *
     * @param clientKey The LS key of the subDb context
     * @since 0.9.60
     */
    private String matchDbid(Hash clientKey) {
        for (FloodfillNetworkDatabaseFacade subdb : getSubNetDBs()) {
            if (subdb.matchClientKey(clientKey))
                return subdb._dbid;
        }
        return null;
    }

    @Override
    public Set<FloodfillNetworkDatabaseFacade> getSubNetDBs() {
        Set<FloodfillNetworkDatabaseFacade> rv = new HashSet<>();
        rv.add(mainNetDB());
        rv.add(multiHomeNetDB());
        rv.addAll(_subDBs.values());
        return rv;
    }

    @Override
    public List<BlindData> getLocalClientsBlindData() {
        List<BlindData> rv = new ArrayList<>();
        for (FloodfillNetworkDatabaseFacade subdb : getSubNetDBs()) {
            rv.addAll(subdb.getBlindData());
        }
        return rv;
    }
}
