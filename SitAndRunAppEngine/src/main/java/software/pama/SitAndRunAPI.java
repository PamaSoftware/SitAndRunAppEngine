package software.pama;


import static software.pama.OfyService.ofy;

import com.google.api.server.spi.config.Api;
import com.google.api.server.spi.config.ApiMethod;
import com.google.api.server.spi.config.Named;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.users.User;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Query;
import java.util.Date;

/**
 * Created by Pawel on 2015-01-17.
 */
@Api(
        name = "sitAndRunApi",
        version = "v2",
        scopes = {Constants.EMAIL_SCOPE},
        clientIds = {Constants.WEB_CLIENT_ID, Constants.ANDROID_CLIENT_ID, Constants.API_EXPLORER_CLIENT_ID},
        audiences = {Constants.ANDROID_AUDIENCE}
)
public class SitAndRunAPI {

    MemcacheService syncCache = MemcacheServiceFactory.getMemcacheService();

    /**
     * logowanie do serwisu
     *
     * @return null jesli uzytkownik jest calkiem nowy, profile jesli uzytkownik juz posiada konto
     */
    @ApiMethod(name = "signIn", path = "signIn")
    public Profile signIn(User user) throws OAuthRequestException{
        if(syncCache.contains(user.getEmail())){
            Profile profile = (Profile) syncCache.get(user.getEmail());
            return profile;
        }
        Key key = Key.create(DatastoreProfile.class, user.getEmail());
        DatastoreProfile datastoreProfile = (DatastoreProfile) ofy().load().key(key).now();
        if(datastoreProfile == null)
            return null;
        Profile p = new Profile(datastoreProfile);
        syncCache.put(user.getEmail(), p);
        return p;
    }

    /**
     * Funkcja wykorzystywana do zakladania nowego konta.
     *
     * @param login nazwa uzytkownika jaka chcemy wykorzystywac.
     * @return null jesli nazwa jest zajeta, profile jesli udalo sie zalozyc konto.
     */
    @ApiMethod(name = "signUp", path = "signUp")
    public Profile signUp(User user, @Named("login") String login) throws OAuthRequestException{
        if(syncCache.contains(user.getEmail())){
            Profile profile = (Profile) syncCache.get(user.getEmail());
            return profile;
        }
        Query<DatastoreProfile> query = ofy().load().type(DatastoreProfile.class).order("login");
        query = query.filter("login =",login);
        if(query.first().now() == null){
            DatastoreProfile datastoreProfile = new DatastoreProfile(user.getEmail(), login);
            ofy().save().entity(datastoreProfile).now();
            Profile p = new Profile(login);
            syncCache.put(user.getEmail(), p);
            return p;
        }else {
            return null;
        }
    }

    /**
     * Funkcja wykorzystywana do usuniecia konta.
     *
     * @return true jesli blad
     */
    @ApiMethod(name = "deleteAccount", path = "deleteAccount")
    public WrappedBoolean deleteAccount(User user) throws OAuthRequestException{
        //TODO jakas koncepcja na odczepienie historii danego uzytkownika od jego encji, by je zachowac, ale gdy ten sam uzytkownik zalozy konto by nie mial starej historii, koncepcja - przepisanie wpisow pod jakiegos innego rodzica UWAGA pamietac ze ID jest unique w ramach rodzica, a nie globalnie
        Key key = Key.create(DatastoreProfile.class, user.getEmail());
        ofy().delete().key(key).now();
        if(syncCache.contains(user.getEmail())){
            syncCache.delete(user.getEmail());
        }
        return new WrappedBoolean(false);
    }

    /**
     * Funkcja wykorzystywana do zasygnalizowania, ze jestesmy gotowi do biegu z randomowa osoba.
     *
     * @param preferences - aspiration and reservation
     * @param gcmID
     * @return true jesli jakis blad
     */
    @ApiMethod(name = "startRunWithRandom", path = "startRunWithRandom")
    public WrappedBoolean startRunWithRandom(User user, Preferences preferences, @Named("gcmID") String gcmID) throws OAuthRequestException{
        //TODO zabawa z memcache, zadbanie o to by system nam nie wyczyscil tych danych
        return new WrappedBoolean(false);
    }

    /**
     * Funkcja wykorzystywana do zasygnalizowania, ze jestesmy gotowi do biegu ze znajomym.
     *
     * @param preferences - aspiration and reservation
     * @param gcmID
     * @param friendsLogin - if given we try to join to friend which should be host, if null we start being host
     * @return true jesli jakis blad
     */
    @ApiMethod(name = "startRunWithFriend", path = "startRunWithFriend")
    public WrappedBoolean startRunWithFriend(User user, Preferences preferences, @Named("gcmID") String gcmID, @Named("friendsLogin") String friendsLogin) throws OAuthRequestException{
        //TODO zabawa z memcache, zadbanie o to by system nam nie wyczyscil tych danych
        return new WrappedBoolean(false);
    }

    /**
     * Funkcja sluzaca do informowania o aktualnym postepie w biegu
     * @param runResults - lista wynikow jakie zapisalismy
     * @param forecast - czas na ile w przod ma wybiec z obliczeniem serwer i zwrocic pozycje przeciwnika, jezeli aplikacja widzi ze regularnie serwer odpowiada srednio po 3 sekundach, powinna wpisac 3 sekundy, aby w momencie otrzymania informacji byla ona mozliwie najbardziej aktualna
     *
     * @return dystans przeciwnika jaki mial po odpowiadajacym czasie
     */
    @ApiMethod(name = "currentRunState", path = "currentRunState")
    public RunInfoPiece currentRunState(User user, RunResults runResults, @Named("forecast") Date forecast) {
        //TODO wpisywanie danych do memcache, oraz wyliczenie w jakim miejscu przeciwnik byl w danym czasie, najlepiej z zasymulowaniem paru sekund do przodu i zwroceniu czasu
        return new RunInfoPiece(1, new Date());
    }
}
