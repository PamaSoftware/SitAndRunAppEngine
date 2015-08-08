package software.pama.run;

import com.google.appengine.api.memcache.MemcacheService;
import com.googlecode.objectify.Key;
import software.pama.datastore.run.CurrentRunInformation;
import software.pama.datastore.run.RunResultDatastore;

import static software.pama.datastore.OfyService.ofy;

/**
 * Created by Pawel on 2015-08-08.
 */
public class CacheOrganizer {
    public static CurrentRunInformation getFromCacheCurrentRunInforamtion(String login, MemcacheService syncCache) {
        MemcacheRunInfo memcacheRunInfo = (MemcacheRunInfo) syncCache.get(login);
        if(memcacheRunInfo == null)
            return null;
        CurrentRunInformation currentRunInformation;
        if(memcacheRunInfo.isRunWithRandom())
            currentRunInformation = (CurrentRunInformation) syncCache.get("RunWithRandom:".concat(login));
        else
            currentRunInformation = (CurrentRunInformation) syncCache.get("RunWithFriend:".concat(memcacheRunInfo.getHostLogin()));
        return currentRunInformation;
    }

    public static void saveToCacheCurrentRunInformation(CurrentRunInformation currentRunInformation, String login, MemcacheService syncCache) {
        syncCache.put(login, new MemcacheRunInfo(currentRunInformation.isRunWithRandom(), currentRunInformation.getHostLogin()));
        if(currentRunInformation.isRunWithRandom())
            syncCache.put("RunWithRandom:".concat(currentRunInformation.getHostLogin()), currentRunInformation);
        else
            syncCache.put("RunWithFriend:".concat(currentRunInformation.getHostLogin()), currentRunInformation);
    }

    public static void saveToCacheRunResult(RunResultDatastore runResultDatastore, String login, MemcacheService syncCache) {
        syncCache.put("RunResult:".concat(login), runResultDatastore);
    }

    public static void saveToCacheRunResultFromDatastore(CurrentRunInformation currentRunInformation, MemcacheService syncCache) {
        RunResultDatastore runResultHost = getFromCacheRunResultDatastore(currentRunInformation.getHostLogin(), syncCache);
        RunResultDatastore runResultOpponent = getFromCacheRunResultDatastore(currentRunInformation.getOpponentLogin(), syncCache);
        if(runResultHost == null)
            saveToCacheRunResultsForHostFromDatastore(currentRunInformation, syncCache);
        if(runResultOpponent == null)
            saveToCacheRunResultsForOpponentFromDatastore(currentRunInformation, syncCache);
    }

    public static RunResultDatastore getFromCacheRunResultDatastore(String login, MemcacheService syncCache) {
        return (RunResultDatastore) syncCache.get("RunResult:".concat(login));
    }

    public static void removeFromCacheWholeRunInformation(String login, MemcacheService syncCache) {
        syncCache.delete(login);
        syncCache.delete("RunWithRandom:".concat(login));
        syncCache.delete("RunWithFriend:".concat(login));
        syncCache.delete("RunResult:".concat(login));
    }

    public static void removeFromCacheWholeRunInformationForPlayerAndBasicInfoForOpponent(String login, String oppLogin, MemcacheService syncCache) {
        syncCache.delete(login);
        syncCache.delete(oppLogin);
        syncCache.delete("RunWithRandom:".concat(login));
        syncCache.delete("RunWithFriend:".concat(login));
        syncCache.delete("RunResult:".concat(login));
    }

    public static void removeFromCacheWholeRunInformation(CurrentRunInformation currentRunInformation, MemcacheService syncCache) {
        String hostLogin = currentRunInformation.getHostLogin();
        String oppLogin = currentRunInformation.getOpponentLogin();
        syncCache.delete(hostLogin);
        syncCache.delete("RunWithRandom:".concat(hostLogin));
        syncCache.delete("RunWithFriend:".concat(hostLogin));
        syncCache.delete("RunResult:".concat(hostLogin));
        syncCache.delete(oppLogin);
        syncCache.delete("RunWithRandom:".concat(oppLogin));
        syncCache.delete("RunWithFriend:".concat(oppLogin));
        syncCache.delete("RunResult:".concat(oppLogin));
    }

    private static boolean saveToCacheRunResultsForHostFromDatastore(CurrentRunInformation currentRunInformation, MemcacheService syncCache) {
        //wyciagniecie z bazy i zapisanie dla hosta
        Key hostKey = Key.create(RunResultDatastore.class, currentRunInformation.getHostRunResultId());
        RunResultDatastore runResultDatastore = (RunResultDatastore) ofy().load().key(hostKey).now();
        if(runResultDatastore == null)
            return false;
        syncCache.put("RunResult:".concat(currentRunInformation.getHostLogin()), runResultDatastore);
        return true;
    }

    private static boolean saveToCacheRunResultsForOpponentFromDatastore(CurrentRunInformation currentRunInformation, MemcacheService syncCache) {
        //wyciagniecie z bazy i zapisanie dla przeciwnika
        Key opponentKey = Key.create(RunResultDatastore.class, currentRunInformation.getOpponentRunResultId());
        RunResultDatastore runResultDatastore = (RunResultDatastore) ofy().load().key(opponentKey).now();
        if(runResultDatastore == null)
            return false;
        syncCache.put("RunResult:".concat(currentRunInformation.getOpponentLogin()), runResultDatastore);
        return true;
    }
}
