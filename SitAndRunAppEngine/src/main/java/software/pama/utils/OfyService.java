package software.pama.utils;

import com.googlecode.objectify.Objectify;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.ObjectifyFactory;
import software.pama.users.datastore.DatastoreProfile;
import software.pama.users.datastore.DatastoreProfileHistory;
import software.pama.users.datastore.DatastoreTotalHistory;
import software.pama.run.datastore.CurrentRunInformation;

/**
 * Created by Pawel on 2015-03-03.
 */
public class OfyService {
    static {
        factory().register(DatastoreProfile.class);
        factory().register(DatastoreProfileHistory.class);
        factory().register(DatastoreTotalHistory.class);
        factory().register(CurrentRunInformation.class);
    }

    public static Objectify ofy() {
        return ObjectifyService.ofy();
    }

    public static ObjectifyFactory factory() {
        return ObjectifyService.factory();
    }
}
