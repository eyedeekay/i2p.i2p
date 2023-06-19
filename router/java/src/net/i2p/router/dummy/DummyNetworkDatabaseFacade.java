package net.i2p.router.dummy;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.i2p.data.DatabaseEntry;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.router.RouterInfo;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.KademliaNetworkDatabaseFacade;
import net.i2p.router.networkdb.kademlia.SegmentedNetworkDatabaseFacade;

public class DummyNetworkDatabaseFacade extends SegmentedNetworkDatabaseFacade {
    private final Map<Hash, RouterInfo> _routers;
    private final RouterContext _context;
    
    public DummyNetworkDatabaseFacade(RouterContext ctx) {
        super(ctx);
        _routers = Collections.synchronizedMap(new HashMap<Hash, RouterInfo>());
        _context = ctx;
    }

    public FloodfillNetworkDatabaseFacade getSubNetDB(String dbid){
        return this;
    }

    public void restart() {}
    public void shutdown() {}
    public void startup() {
        RouterInfo info = _context.router().getRouterInfo();
        _routers.put(info.getIdentity().getHash(), info);
    }
    
    public DatabaseEntry lookupLocally(Hash key) { return null; }
    public DatabaseEntry lookupLocallyWithoutValidation(Hash key) { return null; }
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {}
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, Hash fromLocalDest) {}
    public LeaseSet lookupLeaseSetLocally(Hash key) { return null; }
    public void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest) {}
    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob,
                                       long timeoutMs, Hash fromLocalDest) {}

    public void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest) {}

    public Destination lookupDestinationLocally(Hash key) { return null; }

    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs) {
        RouterInfo info = lookupRouterInfoLocally(key);
        if (info == null) 
            _context.jobQueue().addJob(onFailedLookupJob);
        else
            _context.jobQueue().addJob(onFindJob);
    }
    public RouterInfo lookupRouterInfoLocally(Hash key) { return _routers.get(key); }

    public void publish(LeaseSet localLeaseSet) {}
    public void publish(RouterInfo localRouterInfo) {}

    public LeaseSet store(Hash key, LeaseSet leaseSet) { return leaseSet; }
    public RouterInfo store(Hash key, RouterInfo routerInfo) {
        RouterInfo rv = _routers.put(key, routerInfo);
        return rv;
    }

    public void unpublish(LeaseSet localLeaseSet) {}
    public void fail(Hash dbEntry) {
        _routers.remove(dbEntry);
    }
    
    public Set<Hash> getAllRouters() { return new HashSet<Hash>(_routers.keySet()); }
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore) { return getAllRouters(); }

    @Override
    public Set<Hash> findNearestRouters(Hash key, int maxNumRouters, Set<Hash> peersToIgnore, String dbid) {
        return findNearestRouters(key, maxNumRouters, peersToIgnore);
    }

    @Override
    public DatabaseEntry lookupLocally(Hash key, String dbid) {
        return lookupLocally(key);
    }

    @Override
    public DatabaseEntry lookupLocallyWithoutValidation(Hash key, String dbid) {
        return lookupLocallyWithoutValidation(key);
    }

    @Override
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, String dbid) {
        lookupLeaseSet(key, onFindJob, onFailedLookupJob, timeoutMs);
    }

    @Override
    public void lookupLeaseSet(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, Hash fromLocalDest,
            String dbid) {
        lookupLeaseSet(key, onFindJob, onFailedLookupJob, timeoutMs, fromLocalDest);
    }

    @Override
    public LeaseSet lookupLeaseSetLocally(Hash key, String dbid) {
        throw new UnsupportedOperationException("Unimplemented method 'lookupLeaseSetLocally'");
    }

    @Override
    public void lookupRouterInfo(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs, String dbid) {
        lookupRouterInfo(key, onFindJob, onFailedLookupJob, timeoutMs);
    }

    @Override
    public RouterInfo lookupRouterInfoLocally(Hash key, String dbid) {
        return lookupRouterInfoLocally(key);
    }

    @Override
    public void lookupLeaseSetRemotely(Hash key, Hash fromLocalDest, String dbid) {
        lookupLeaseSetRemotely(key, fromLocalDest);
    }

    @Override
    public void lookupLeaseSetRemotely(Hash key, Job onFindJob, Job onFailedLookupJob, long timeoutMs,
            Hash fromLocalDest, String dbid) {
        lookupLeaseSetRemotely(key, onFindJob, onFailedLookupJob, timeoutMs, fromLocalDest);
    }

    @Override
    public void lookupDestination(Hash key, Job onFinishedJob, long timeoutMs, Hash fromLocalDest, String dbid) {
        lookupDestination(key, onFinishedJob, timeoutMs, fromLocalDest);
    }

    @Override
    public Destination lookupDestinationLocally(Hash key, String dbid) {
        return lookupDestinationLocally(key);
    }

    @Override
    public LeaseSet store(Hash key, LeaseSet leaseSet, String dbid) throws IllegalArgumentException {
        return this.store(key, leaseSet);
    }

    @Override
    public RouterInfo store(Hash key, RouterInfo routerInfo, String dbid) throws IllegalArgumentException {
        return this.store(key, routerInfo);
    }

    @Override
    public void publish(RouterInfo localRouterInfo, String dbid) throws IllegalArgumentException {
        this.publish(localRouterInfo);
    }

    @Override
    public void publish(LeaseSet localLeaseSet, String dbid) {
        this.publish(localLeaseSet);
    }

    @Override
    public void unpublish(LeaseSet localLeaseSet, String dbid) {
        this.unpublish(localLeaseSet);
    }

    @Override
    public void fail(Hash dbEntry, String dbid) {
        this.fail(dbEntry);
    }

    @Override
    public Set<Hash> getAllRouters(String dbid) {
        return this.getAllRouters();
    }
}
