package net.i2p.router.networkdb.kademlia;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Hash;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.i2np.DatabaseLookupMessage;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.HandlerJobBuilder;
import net.i2p.router.Job;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Build a HandleDatabaseLookupMessageJob whenever a DatabaseLookupMessage
 * arrives
 *
 */
public class FloodfillDatabaseLookupMessageHandler implements HandlerJobBuilder {
    private RouterContext _context;
    private FloodfillNetworkDatabaseFacade _facade;
    private Log _log;
    private final long _msgIDBloomXor = RandomSource.getInstance().nextLong(I2NPMessage.MAX_ID_VALUE);

    public FloodfillDatabaseLookupMessageHandler(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        _context = context;
        _facade = facade;
        _log = context.logManager().getLog(FloodfillDatabaseLookupMessageHandler.class);
        _context.statManager().createRateStat("netDb.lookupsReceived", "How many netDb lookups have we received?", "NetworkDatabase", new long[] { 60*60*1000l });
        _context.statManager().createRateStat("netDb.lookupsDropped", "How many netDb lookups did we drop due to throttling?", "NetworkDatabase", new long[] { 60*60*1000l });
        _context.statManager().createRateStat("netDb.nonFFLookupsDropped", "How many netDb lookups did we drop due to us not being a floodfill?", "NetworkDatabase", new long[] { 60*60*1000l });
        // following are for ../HDLMJ
        _context.statManager().createRateStat("netDb.lookupsHandled", "How many netDb lookups have we handled?",
                "NetworkDatabase", new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatched",
                "How many netDb lookups did we have the data for?", "NetworkDatabase", new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLeaseSet",
                "How many netDb leaseSet lookups did we have the data for?", "NetworkDatabase",
                new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedReceivedPublished",
                "How many netDb lookups did we have the data for that were published to us?", "NetworkDatabase",
                new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalClosest",
                "How many netDb lookups for local data were received where we are the closest peers?",
                "NetworkDatabase", new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedLocalNotClosest",
                "How many netDb lookups for local data were received where we are NOT the closest peers?",
                "NetworkDatabase", new long[] { 60 * 60 * 1000l });
        _context.statManager().createRateStat("netDb.lookupsMatchedRemoteNotClosest",
                "How many netDb lookups for remote data were received where we are NOT the closest peers?",
                "NetworkDatabase", new long[] { 60 * 60 * 1000l });
    }

    public Job createJob(I2NPMessage receivedMessage, RouterIdentity from, Hash fromHash) {
        _context.statManager().addRateData("netDb.lookupsReceived", 1);

        DatabaseLookupMessage dlm = (DatabaseLookupMessage)receivedMessage;
        if (dlm.getSearchType() == DatabaseLookupMessage.Type.EXPL &&
            !_context.netDb().floodfillEnabled()) {
            if (_log.shouldLog(Log.WARN)) 
                _log.warn("[dbid: " + _facade
                          + "] Dropping " + dlm.getSearchType()
                          + " lookup request for " + dlm.getSearchKey()
                          + " (we are not a floodfill), reply was to: "
                          + dlm.getFrom() + " tunnel: " + dlm.getReplyTunnel());
            _context.statManager().addRateData("netDb.nonFFLookupsDropped", 1);
            return null;
        }

        if (!_facade.shouldThrottleLookup(dlm.getFrom(), dlm.getReplyTunnel())
                || _context.routerHash().equals(dlm.getSearchKey())) {
            Job j = new HandleFloodfillDatabaseLookupMessageJob(_context, dlm, from, fromHash, _msgIDBloomXor);
            return j;
        } else {
            if (_log.shouldLog(Log.WARN)) 
                _log.warn("[dbid: " + _facade
                          + "] Dropping " + dlm.getSearchType()
                          + " lookup request for " + dlm.getSearchKey()
                          + " (throttled), reply was to: " + dlm.getFrom()
                          + " tunnel: " + dlm.getReplyTunnel());
            _context.statManager().addRateData("netDb.lookupsDropped", 1);
            return null;
        }
    }
}
