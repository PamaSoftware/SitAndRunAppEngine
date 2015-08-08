package software.pama.administration;


import com.google.api.server.spi.response.BadRequestException;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.users.User;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Query;
import software.pama.communication.Profile;
import software.pama.communication.wrappers.WrappedBoolean;
import software.pama.datastore.users.DatastoreProfile;

import static software.pama.datastore.OfyService.ofy;

/**
 * Created by Pawel on 2015-08-08.
 */
public class Account {
    public static Profile signIn(User user, MemcacheService syncCache) throws OAuthRequestException{
        return getUserProfile(user, syncCache);
    }

    public static Profile signUp(User user, String login, MemcacheService syncCache) throws OAuthRequestException, BadRequestException {
        if(checkIfLoginExist(login))
            return null;
        DatastoreProfile datastoreProfile = new DatastoreProfile(user.getEmail(), login);
        ofy().save().entity(datastoreProfile).now();
        Profile p = new Profile(login);
        syncCache.put(user.getEmail(), p);
        return p;
    }

    public static WrappedBoolean deleteAccount(User user, MemcacheService syncCache) throws OAuthRequestException{
        Key key = Key.create(DatastoreProfile.class, user.getEmail());
        ofy().delete().keys(ofy().load().ancestor(key).keys().list());
        ofy().delete().key(key).now();
        syncCache.delete(user.getEmail());
        return new WrappedBoolean(true);
    }

    public static DatastoreProfile getDatastoreProfile(User user) throws OAuthRequestException{
        Key key = Key.create(DatastoreProfile.class, user.getEmail());
        return (DatastoreProfile) ofy().load().key(key).now();
    }

    public static Profile getUserProfile(User user, MemcacheService syncCache) throws OAuthRequestException {
        Profile profile = (Profile) syncCache.get(user.getEmail());
        if(profile != null)
            return profile;
        DatastoreProfile datastoreProfile = getDatastoreProfile(user);
        if(datastoreProfile == null)
            return null;
        Profile p = new Profile(datastoreProfile);
        syncCache.put(user.getEmail(), p);
        return p;
    }

    public static boolean checkIfLoginExist(String login) {
        Query<DatastoreProfile> query = ofy().load().type(DatastoreProfile.class).order("login");
        query = query.filter("login =",login);
        return query.first().now() != null;
    }
}
