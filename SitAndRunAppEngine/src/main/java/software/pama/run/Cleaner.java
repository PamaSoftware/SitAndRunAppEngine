package software.pama.run;

import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.users.User;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Query;
import software.pama.administration.Account;
import software.pama.communication.Profile;
import software.pama.datastore.run.CurrentRunInformation;
import software.pama.datastore.run.RunResultDatastore;
import software.pama.datastore.run.with.friend.RunMatcher;
import software.pama.datastore.users.DatastoreProfile;

import java.util.List;

import static software.pama.datastore.OfyService.ofy;

/**
 * Created by Pawel on 2015-08-08.
 */
public class Cleaner {
    public static void cancelAllActiveUserRunProcesses(User user, MemcacheService syncCache) throws OAuthRequestException {
        cancelAllPairingProcesses(user, syncCache);
        cancelAllActiveUserRuns(user, syncCache);
    }

    public static void cancelAllPairingProcesses(User user, MemcacheService syncCache) throws OAuthRequestException {
        Profile ourProfile = Account.signIn(user, syncCache);
        if(ourProfile == null)
            return;

        Query<RunMatcher> query = ofy().load().type(RunMatcher.class).order("opponentLogin");
        query = query.filter("opponentLogin =",ourProfile.getLogin());
        List<RunMatcher> runMatcherList = query.list();
        Query<RunMatcher> query2 = ofy().load().type(RunMatcher.class).order("hostLogin");
        query2 = query2.filter("hostLogin =",ourProfile.getLogin());
        List<RunMatcher> runMatcherList2 = query2.list();

        syncCache.delete("runMatch:".concat(ourProfile.getLogin()));

        if(!runMatcherList.isEmpty())
            ofy().delete().entities(runMatcherList).now();
        if(!runMatcherList2.isEmpty())
            ofy().delete().entities(runMatcherList2).now();
    }

    public static void cancelAllActiveUserRuns(User user, MemcacheService syncCache) throws OAuthRequestException {
        cancelAllActiveUserRunForHost(user, syncCache);
        cancelAllActiveUserRunForOpponent(user, syncCache);
    }

    private static void cancelAllActiveUserRunForHost(User user, MemcacheService syncCache) throws OAuthRequestException {
        DatastoreProfile p = Account.getDatastoreProfile(user);
        if(p == null)
            return;
        CacheOrganizer.removeFromCacheWholeRunInformation(p.getLogin(), syncCache);
        CurrentRunInformation currentRunInformation = Finder.findNotFinishedRunForHost(p.getLogin());
        if(currentRunInformation == null)
            return;
        CacheOrganizer.removeFromCacheWholeRunInformation(currentRunInformation, syncCache);
        if(currentRunInformation.isRunWithRandom()) {
            p.addLoseRace();
            ofy().delete().entity(currentRunInformation).now();
            ofy().save().entity(p).now();
            return;
        } else {
            if(currentRunInformation.getStarted())
                p.addLoseRace();
            //usuniecie wpisu hosta
            //jesli brak wpisu opponenta usuniecie calego stanu wyscigu
            Key playerKey = Key.create(RunResultDatastore.class, currentRunInformation.getHostRunResultId());
            RunResultDatastore runResultDatastore = (RunResultDatastore) ofy().load().key(playerKey).now();
            if(currentRunInformation.getOpponentLogin().equals("")){
                if(currentRunInformation.getStarted())
                    ofy().save().entity(p).now();
                ofy().delete().entities(runResultDatastore, currentRunInformation).now();
            } else {
                currentRunInformation.setHostLogin("");
                if(currentRunInformation.getStarted())
                    ofy().save().entities(currentRunInformation, p).now();
                else
                    ofy().save().entities(currentRunInformation).now();
                ofy().delete().entity(runResultDatastore).now();
            }
            return;
        }
    }

    private static void cancelAllActiveUserRunForOpponent(User user, MemcacheService syncCache) throws OAuthRequestException {
        DatastoreProfile p = Account.getDatastoreProfile(user);
        if(p == null)
            return;
        CacheOrganizer.removeFromCacheWholeRunInformation(p.getLogin(), syncCache);
        CurrentRunInformation currentRunInformation = Finder.findNotFinishedRunForOpponent(p.getLogin());
        if(currentRunInformation == null)
            return;
        CacheOrganizer.removeFromCacheWholeRunInformation(currentRunInformation, syncCache);
        if(currentRunInformation.getStarted())
            p.addLoseRace();
        //usuniecie wpisu hosta
        //jesli brak wpisu opponenta usuniecie calego stanu wyscigu
        Key playerKey = Key.create(RunResultDatastore.class, currentRunInformation.getOpponentRunResultId());
        RunResultDatastore runResultDatastore = (RunResultDatastore) ofy().load().key(playerKey).now();
        if(currentRunInformation.getHostLogin().equals("")){
            if(currentRunInformation.getStarted())
                ofy().save().entity(p).now();
            ofy().delete().entities(runResultDatastore, currentRunInformation).now();
        } else {
            currentRunInformation.setOpponentLogin("");
            if(currentRunInformation.getStarted())
                ofy().save().entities(currentRunInformation, p).now();
            else
                ofy().save().entities(currentRunInformation).now();
            ofy().delete().entity(runResultDatastore).now();
        }
        return;
    }
}
