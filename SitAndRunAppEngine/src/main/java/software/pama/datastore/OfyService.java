package software.pama.datastore;

import com.googlecode.objectify.Objectify;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.ObjectifyFactory;
import software.pama.datastore.run.RunResultDatastore;
import software.pama.datastore.run.with.friend.RunMatcher;
import software.pama.datastore.users.DatastoreProfile;
import software.pama.datastore.run.history.DatastoreProfileHistory;
import software.pama.datastore.run.history.DatastoreTotalHistory;
import software.pama.datastore.run.CurrentRunInformation;

/**
 * Created by Pawel on 2015-03-03.
 */
public class OfyService {
    static {
        factory().register(DatastoreProfile.class);
        factory().register(DatastoreProfileHistory.class);
        factory().register(DatastoreTotalHistory.class);
        factory().register(CurrentRunInformation.class);
        factory().register(RunMatcher.class);
        factory().register(RunResultDatastore.class);
    }

    public static Objectify ofy() {
        return ObjectifyService.ofy();
    }

    public static ObjectifyFactory factory() {
        return ObjectifyService.factory();
    }
}
