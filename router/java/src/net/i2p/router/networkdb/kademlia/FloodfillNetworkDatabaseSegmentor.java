package net.i2p.router.networkdb.kademlia;

import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
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

public class FloodfillNetworkDatabaseSegmentor extends SegmentedNetworkDatabaseFacade {
    private final RouterContext _context;
    private Map<String, FloodfillNetworkDatabaseFacade> _subDBs = new HashMap<String, FloodfillNetworkDatabaseFacade>();

    public FloodfillNetworkDatabaseSegmentor(RouterContext context) {
        super(context);
        _context = context;
    }

    /*public FloodfillNetworkDatabaseFacade getSubNetDB() {
        return this;
    }*/
    public FloodfillNetworkDatabaseFacade getSubNetDB(String id) {
        if (id == null || id.isEmpty()) {
            return this;
        }
        FloodfillNetworkDatabaseFacade subdb = _subDBs.get(id);
        if (subdb == null) {
            subdb = new FloodfillNetworkDatabaseFacade(_context, id);
            _subDBs.put(id, subdb);
            subdb.startup();
        }
        return subdb;
    }

    public synchronized void startup() {
        super.startup();
        for (FloodfillNetworkDatabaseFacade subdb : _subDBs.values()) {
            subdb.startup();
        }
    }

    protected void createHandlers(String dbid) {
        super.createHandlers(dbid);
    }

    /**
     * If we are floodfill, turn it off and tell everybody.
     * 
     * @since 0.8.9
     */
    public synchronized void shutdown() {
        // shut down every entry in _subDBs
        for (FloodfillNetworkDatabaseFacade subdb : _subDBs.values()) {
            subdb.shutdown();
        }
        super.shutdown();
    }

    /**
     * This maybe could be shorter than
     * RepublishLeaseSetJob.REPUBLISH_LEASESET_TIMEOUT,
     * because we are sending direct, but unresponsive floodfills may take a while
     * due to timeouts.
     */
    static final long PUBLISH_TIMEOUT = 90 * 1000;

    /**
     * Send our RI to the closest floodfill.
     * 
     * @throws IllegalArgumentException if the local router info is invalid
     */
    public void publish(RouterInfo localRouterInfo) throws IllegalArgumentException {
        super.publish(localRouterInfo);
    }

    /**
     * Send out a store.
     *
     * @param key         the DatabaseEntry hash
     * @param onSuccess   may be null, always called if we are ff and ds is an RI
     * @param onFailure   may be null, ignored if we are ff and ds is an RI
     * @param sendTimeout ignored if we are ff and ds is an RI
     * @param toIgnore    may be null, if non-null, all attempted and skipped
     *                    targets will be added as of 0.9.53,
     *                    unused if we are ff and ds is an RI
     */
    void sendStore(Hash key, DatabaseEntry ds, Job onSuccess, Job onFailure, long sendTimeout, Set<Hash> toIgnore) {
        super.sendStore(key, ds, onSuccess, onFailure, sendTimeout, toIgnore);
    }

    /**
     * Increments and tests.
     * 
     * @since 0.7.11
     */
    boolean shouldThrottleFlood(Hash key) {
        return super.shouldThrottleFlood(key);
    }

    /**
     * Increments and tests.
     * 
     * @since 0.7.11
     */
    boolean shouldThrottleLookup(Hash from, TunnelId id) {
        return super.shouldThrottleLookup(from, id);
    }

    /**
     * If we are floodfill AND the key is not throttled,
     * flood it, otherwise don't.
     *
     * @return if we did
     * @since 0.9.36 for NTCP2
     */
    public boolean floodConditional(DatabaseEntry ds) {
        return super.floodConditional(ds);
    }

    /**
     * Send to a subset of all floodfill peers.
     * We do this to implement Kademlia within the floodfills, i.e.
     * we flood to those closest to the key.
     */
    public void flood(DatabaseEntry ds) {
        super.flood(ds);
    }

    /**
     * @param type      database store type
     * @param lsSigType may be null
     * @since 0.9.39
     */
    /*
     * private boolean shouldFloodTo(Hash key, int type, SigType lsSigType, Hash
     * peer, RouterInfo target) {
     * return super.shouldFloodTo(key, type, lsSigType, peer,
     * target);
     * }
     */

    protected PeerSelector createPeerSelector() {
        return super.createPeerSelector();
    }

    /**
     * Public, called from console. This wakes up the floodfill monitor,
     * which will rebuild the RI and log in the event log,
     * and call setFloodfillEnabledFromMonitor which really sets it.
     */
    public synchronized void setFloodfillEnabled(boolean yes) {
        super.setFloodfillEnabled(yes);
    }

    /**
     * Package private, called from FloodfillMonitorJob. This does not wake up the
     * floodfill monitor.
     * 
     * @since 0.9.34
     */
    synchronized void setFloodfillEnabledFromMonitor(boolean yes) {
        super.setFloodfillEnabledFromMonitor(yes);
    }

    public boolean floodfillEnabled() {
        return super.floodfillEnabled();
    }

    /**
     * @param peer may be null, returns false if null
     */
    public static boolean isFloodfill(RouterInfo peer) {
        return FloodfillNetworkDatabaseSegmentor.isFloodfill(peer);
    }

    public List<RouterInfo> getKnownRouterData() {
        return super.getKnownRouterData();
    }

    /**
     * Lookup using exploratory tunnels.
     *
     * Caller should check negative cache and/or banlist before calling.
     *
     * Begin a kademlia style search for the key specified, which can take up to
     * timeoutMs and
     * will fire the appropriate jobs on success or timeout (or if the kademlia
     * search completes
     * without any match)
     *
     * @return null always
     */

    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease) {
        return super.search(key, onFindJob, onFailedLookupJob, timeoutMs, isLease);
    }

    /**
     * Lookup using the client's tunnels.
     *
     * Caller should check negative cache and/or banlist before calling.
     *
     * @param fromLocalDest use these tunnels for the lookup, or null for
     *                      exploratory
     * @return null always
     * @since 0.9.10
     */
    SearchJob search(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, boolean isLease,
            Hash fromLocalDest) {
        return super.search(key, onFindJob, onFailedLookupJob, timeoutMs, isLease, fromLocalDest);
    }

    /**
     * Must be called by the search job queued by search() on success or failure
     */
    void complete(Hash key) {
        super.complete(key);
    }

    /**
     * list of the Hashes of currently known floodfill peers;
     * Returned list will not include our own hash.
     * List is not sorted and not shuffled.
     */
    public List<Hash> getFloodfillPeers() {
        return super.getFloodfillPeers();
    }

    /** @since 0.7.10 */
    boolean isVerifyInProgress(Hash h) {
        return super.isVerifyInProgress(h);
    }

    /** @since 0.7.10 */
    void verifyStarted(Hash h) {
        super.verifyStarted(h);
    }

    /** @since 0.7.10 */
    void verifyFinished(Hash h) {
        super.verifyFinished(h);
    }

    /**
     * Search for a newer router info, drop it from the db if the search fails,
     * unless just started up or have bigger problems.
     */

    protected void lookupBeforeDropping(Hash peer, RouterInfo info) {
        super.lookupBeforeDropping(peer, info);
    }

    /**
     * Return the RouterInfo structures for the routers closest to the given key.
     * At most maxNumRouters will be returned
     *
     * @param key           The key
     * @param maxNumRouters The maximum number of routers to return
     * @param peersToIgnore Hash of routers not to include
     */
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, String dbid) {
        return null;
    }

    /**
     * @return RouterInfo, LeaseSet, or null
     * @since 0.8.3
     */
    public DatabaseEntry lookupLocally(Hash key, String dbid) {
        return this.getSubNetDB(dbid).lookupLocally(key);
    }

    /**
     * Not for use without validation
     * 
     * @return RouterInfo, LeaseSet, or null, NOT validated
     * @since 0.9.38
     */
    public DatabaseEntry lookupLocallyWithoutValidation(Hash key, String dbid) {
        return this.getSubNetDB(dbid).lookupLocallyWithoutValidation(key);
    }

    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, String dbid) {
        this.getSubNetDB(dbid).lookupLeaseSet(key, onFindJob, onFailedLookupJob, timeoutMs);
    }

    /**
     * Lookup using the client's tunnels
     * 
     * @param fromLocalDest use these tunnels for the lookup, or null for
     *                      exploratory
     * @since 0.9.10
     */
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, Hash fromLocalDest,
            String dbid) {
        this.getSubNetDB(dbid).lookupLeaseSet(key, onFindJob, onFailedLookupJob, timeoutMs, fromLocalDest);
    }

    public LeaseSet lookupLeaseSetLocally(Hash key, String dbid) {
        return this.getSubNetDB(dbid).lookupLeaseSetLocally(key);
    }

    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, String dbid) {
        this.getSubNetDB(dbid).lookupRouterInfo(key, onFindJob, onFailedLookupJob, timeoutMs);
    }

    public RouterInfo lookupRouterInfoLocally(Hash key, String dbid) {
        return this.getSubNetDB(dbid).lookupRouterInfoLocally(key);
    }

    /**
     * Unconditionally lookup using the client's tunnels.
     * No success or failed jobs, no local lookup, no checks.
     * Use this to refresh a leaseset before expiration.
     *
     * @param fromLocalDest use these tunnels for the lookup, or null for
     *                      exploratory
     * @since 0.9.25
     */
    public void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest, String dbid) {
        this.getSubNetDB(dbid).lookupLeaseSetRemotely(key, fromLocalDest);
    }

    /**
     * Unconditionally lookup using the client's tunnels.
     *
     * @param fromLocalDest     use these tunnels for the lookup, or null for
     *                          exploratory
     * @param onFindJob         may be null
     * @param onFailedLookupJob may be null
     * @since 0.9.47
     */
    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob,
            long timeoutMs, Hash fromLocalDest, String dbid) {
        this.getSubNetDB(dbid).lookupLeaseSetRemotely(key, onFindJob, onFailedLookupJob, timeoutMs, fromLocalDest);
    }

    /**
     * Lookup using the client's tunnels
     * Succeeds even if LS validation fails due to unsupported sig type
     *
     * @param fromLocalDest use these tunnels for the lookup, or null for
     *                      exploratory
     * @since 0.9.16
     */
    public void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest, String dbid) {
        this.getSubNetDB(dbid).lookupDestination(key, onFinishedJob, timeoutMs, fromLocalDest);
    }

    /**
     * Lookup locally in netDB and in badDest cache
     * Succeeds even if LS validation failed due to unsupported sig type
     *
     * @since 0.9.16
     */
    public Destination lookupDestinationLocally(Hash key, String dbid) {
        return this.getSubNetDB(dbid).lookupDestinationLocally(key);
    }

    /**
     * @return the leaseSet if another leaseSet already existed at that key
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public LeaseSet store(Hash key, LeaseSet leaseSet, String dbid) throws IllegalArgumentException {
        return null;
    }

    /**
     * @return the routerInfo if another router already existed at that key
     *
     * @throws IllegalArgumentException if the data is not valid
     */
    public RouterInfo store(Hash key, RouterInfo routerInfo, String dbid) throws IllegalArgumentException {
        return null;
    }

    /**
     * @return the old entry if it already existed at that key
     * @throws IllegalArgumentException if the data is not valid
     * @since 0.9.16
     */
    public DatabaseEntry store(Hash key, DatabaseEntry entry, String dbid) throws IllegalArgumentException {
        if (entry.getType() == DatabaseEntry.KEY_TYPE_ROUTERINFO)
            return store(key, (RouterInfo) entry);
        if (entry.getType() == DatabaseEntry.KEY_TYPE_LEASESET)
            return store(key, (LeaseSet) entry);
        throw new IllegalArgumentException("unknown type");
    }

    /**
     * @throws IllegalArgumentException if the local router is not valid
     */
    public void publish(RouterInfo localRouterInfo, String dbid) throws IllegalArgumentException {
        this.getSubNetDB(dbid).publish(localRouterInfo);
    }

    public void publish(LeaseSet localLeaseSet, String dbid) {
        this.getSubNetDB(dbid).publish(localLeaseSet);
    }

    public void unpublish(LeaseSet localLeaseSet, String dbid) {
        this.getSubNetDB(dbid).unpublish(localLeaseSet);
    }

    public void fail(Hash dbEntry, String dbid) {
        this.getSubNetDB(dbid).fail(dbEntry);
    }

    public void fail(Hash dbEntry) {
        super.fail(dbEntry);
    }

    /**
     * The last time we successfully published our RI.
     * 
     * @since 0.9.9
     */
    public long getLastRouterInfoPublishTime(String dbid) {
        return this.getSubNetDB(dbid).getLastRouterInfoPublishTime();
    }

    public Set<Hash> getAllRouters(String dbid) {
        return this.getSubNetDB(dbid).getAllRouters();
    }

    public Set<Hash> getAllRouters() {
        return super.getAllRouters();
    }

    public int getKnownRouters(String dbid) {
        return this.getSubNetDB(dbid).getKnownRouters();
    }

    public int getKnownLeaseSets(String dbid) {
        return this.getSubNetDB(dbid).getKnownLeaseSets();
    }

    public boolean isInitialized(String dbid) {
        return this.getSubNetDB(dbid).isInitialized();
    }

    public void rescan(String dbid) {
        this.getSubNetDB(dbid).rescan();
    }

    /** Debug only - all user info moved to NetDbRenderer in router console */
    public void renderStatusHTML(Writer out, String dbid) throws IOException {
        super.renderStatusHTML(out);
    }

    /** public for NetDbRenderer in routerconsole */
    public Set<LeaseSet> getLeases(String dbid) {
        return this.getSubNetDB(dbid).getLeases();
    }

    /** public for NetDbRenderer in routerconsole */
    public Set<RouterInfo> getRouters(String dbid) {
        return this.getSubNetDB(dbid).getRouters();
    }

    /** @since 0.9 */
    public ReseedChecker reseedChecker() {
        return super.reseedChecker();
    };

    /**
     * For convenience, so users don't have to cast to FNDF, and unit tests using
     * Dummy NDF will work.
     *
     * @return false; FNDF overrides to return actual setting
     * @since IPv6
     */
    public boolean floodfillEnabled(String dbid) {
        return this.getSubNetDB(dbid).floodfillEnabled();
    };

    /**
     * Is it permanently negative cached?
     *
     * @param key only for Destinations; for RouterIdentities, see Banlist
     * @since 0.9.16
     */
    public boolean isNegativeCachedForever(Hash key, String dbid) {
        return this.getSubNetDB(dbid).isNegativeCached(key);
    }

    /**
     * @param spk unblinded key
     * @return BlindData or null
     * @since 0.9.40
     */
    public BlindData getBlindData(SigningPublicKey spk, String dbid) {
        return this.getSubNetDB(dbid).getBlindData(spk);
    }

    /**
     * @param bd new BlindData to put in the cache
     * @since 0.9.40
     */
    public void setBlindData(BlindData bd, String dbid) {
        this.getSubNetDB(dbid).setBlindData(bd);
    }

    /**
     * For console ConfigKeyringHelper
     * 
     * @since 0.9.41
     */
    public List<BlindData> getBlindData(String dbid) {
        return this.getSubNetDB(dbid).getBlindData();
    }

    /**
     * For console ConfigKeyringHelper
     * 
     * @return true if removed
     * @since 0.9.41
     */
    public boolean removeBlindData(SigningPublicKey spk, String dbid) {
        return this.getSubNetDB(dbid).removeBlindData(spk);
    }

    /**
     * Notify the netDB that the routing key changed at midnight UTC
     *
     * @since 0.9.50
     */
    public void routingKeyChanged(String dbid) {
        this.getSubNetDB(dbid).routingKeyChanged();
    }

    @Override
    public void restart() {
        super.restart();
    }

    @Override
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore) {
        return super.findNearestRouters(key, maxNumRouters, peersToIgnore);
    }

    @Override
    public DatabaseEntry lookupLocally(Hash key) {
        return super.lookupLocally(key);
    }

    @Override
    public DatabaseEntry lookupLocallyWithoutValidation(Hash key) {
        return super.lookupLocallyWithoutValidation(key);
    }

    @Override
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        super.lookupLeaseSet(key, onFindJob, onFailedLookupJob, timeoutMs, key);
    }

    @Override
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, Hash fromLocalDest) {
        super.lookupLeaseSet(key, onFindJob, onFailedLookupJob, timeoutMs, fromLocalDest);
    }

    @Override
    public LeaseSet lookupLeaseSetLocally(Hash key) {
        return super.lookupLeaseSetLocally(key);
    }

    @Override
    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        super.lookupRouterInfo(key, onFindJob, onFailedLookupJob, timeoutMs);
    }

    @Override
    public RouterInfo lookupRouterInfoLocally(Hash key) {
        return super.lookupRouterInfoLocally(key);
    }

    @Override
    public void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest) {
        super.lookupLeaseSetRemotely(key, fromLocalDest);
    }

    @Override
    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs,
            Hash fromLocalDest) {
        super.lookupLeaseSetRemotely(key, onFindJob, onFailedLookupJob, timeoutMs, fromLocalDest);
    }

    @Override
    public void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest) {
        super.lookupDestination(key, onFinishedJob, timeoutMs, fromLocalDest);
    }

    @Override
    public Destination lookupDestinationLocally(Hash key) {
        return super.lookupDestinationLocally(key);
    }

    @Override
    public LeaseSet store(Hash key, LeaseSet leaseSet) throws IllegalArgumentException {
        return super.store(key, leaseSet);
    }

    @Override
    public RouterInfo store(Hash key, RouterInfo routerInfo) throws IllegalArgumentException {
        return super.store(key, routerInfo);
    }

    @Override
    public void publish(LeaseSet localLeaseSet) {
        super.publish(localLeaseSet);
    }

    @Override
    public void unpublish(LeaseSet localLeaseSet) {
        super.unpublish(localLeaseSet);
    }
}
