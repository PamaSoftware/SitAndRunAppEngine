package software.pama;

import com.googlecode.objectify.Objectify;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.ObjectifyFactory;

/**
 * Created by Pawel on 2015-03-03.
 */
public class OfyService {
    static {
        factory().register(DatastoreProfile.class);
        factory().register(DatastoreProfileHistory.class);
        factory().register(DatastoreTotalHistory.class);
        factory().register(MemcacheRunInfo.class);
    }

    public static Objectify ofy() {
        return ObjectifyService.ofy();
    }

    public static ObjectifyFactory factory() {
        return ObjectifyService.factory();
    }
}
