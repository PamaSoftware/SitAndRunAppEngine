package software.pama;


import static software.pama.datastore.OfyService.ofy;

import com.google.api.server.spi.config.Api;
import com.google.api.server.spi.config.ApiMethod;
import com.google.api.server.spi.config.Named;
import com.google.api.server.spi.response.BadRequestException;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.users.User;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.cmd.Query;
import software.pama.administration.Account;
import software.pama.communication.OpponentPositionInfo;
import software.pama.run.*;
import software.pama.datastore.run.CurrentRunInformation;
import software.pama.datastore.run.RunResultDatastore;
import software.pama.datastore.run.with.friend.RunMatcher;
import software.pama.datastore.users.DatastoreProfile;
import software.pama.datastore.run.history.DatastoreProfileHistory;
import software.pama.datastore.run.history.DatastoreTotalHistory;
import software.pama.communication.Profile;
import software.pama.utils.DateDifferenceCounter;
import software.pama.communication.RunPreferences;
import software.pama.communication.RunStartInfo;
import software.pama.utils.validation.Validator;
import software.pama.communication.wrappers.WrappedBoolean;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created by Pawel on 2015-01-17.
 *
 */
@Api(
        name = "sitAndRunApi",
        version = "v2",
        scopes = {Constants.EMAIL_SCOPE},
        clientIds = {Constants.WEB_CLIENT_ID, Constants.ANDROID_CLIENT_ID, Constants.ANDROID_CLIENT_ID2, Constants.API_EXPLORER_CLIENT_ID},
        audiences = {Constants.ANDROID_AUDIENCE}
)
public class SitAndRunAPI {

    MemcacheService syncCache = MemcacheServiceFactory.getMemcacheService();

    /**
     * Logowanie do serwisu
     *
     * @return profil uzytkownika, jesli uzytkownik istnieje, null jesli uzytkownik nie istnieje
     */
    @ApiMethod(name = "signIn", path = "signIn")
    public Profile signIn(User user) throws OAuthRequestException{
        return Account.signIn(user, syncCache);
    }

    /**
     * Funkcja wykorzystywana do zakladania nowego konta.
     *
     * @param login nazwa uzytkownika jaka chcemy wykorzystywac (a-z, 0-9)
     * @return null jesli nazwa jest zajeta, profil uzytkownika jesli udalo sie zalozyc konto.
     *
     * @throws BadRequestException jesli przekazany login nie spelnia regul
     */
    @ApiMethod(name = "signUp", path = "signUp")
    public Profile signUp(User user, @Named("login") String login) throws OAuthRequestException, BadRequestException{
        login = login.toLowerCase();
        if(!Validator.isLoginCorrect(login))
            throw new BadRequestException("Bad parameter format");
        return Account.signUp(user, login, syncCache);
    }

    /**
     * Funkcja wykorzystywana do usuniecia konta.
     *
     * @return false jesli blad
     */
    @ApiMethod(name = "deleteAccount", path = "deleteAccount")
    public WrappedBoolean deleteAccount(User user) throws OAuthRequestException{
        Cleaner.cancelAllActiveUserRunProcesses(user, syncCache);
        return Account.deleteAccount(user, syncCache);
    }

    /**
     * Funkcja wykorzystywana do zainicjowania biegu z losowym przeciwnikiem.
     *
     * @param runPreferences - dystans na jakim chcemy rywalizowac, okreslony jako aspiracja oraz rezerwacja
     * @return RunStartInfo(dystans na jakim odbedzie sie bieg, czas do startu wyscigu)
     */
    @ApiMethod(name = "startRunWithRandom", path = "startRunWithRandom")
    public RunStartInfo startRunWithRandom(User user, RunPreferences runPreferences) throws OAuthRequestException, BadRequestException{
        /*
        Opis działania:
        Sprawdzamy czy przekazane parametry sa poprawne.
        Sprawdzamy istnienie uzytkownika.
        Sprawdzamy srednia predkosc uzytkownika na podanym zakresie (preferences), korzystajac z historii.
        Jesli brak danych historycznych:
            Nie bedziemy uwzgledniac parametru predkosci przy doborze przeciwnika.
        Jezeli uwzgledniamy predkosc:
            Znajdujemy w historii wszystkie biegi mieszczace sie w preferencjach oraz odbiegajace od sredniej o nie wiecej niz 10%.
            Jezeli brak wynikow:
                Powiekszamy margines o 10% i powtarzamy do skutku.
            Jezeli znalezlismy:
                Dobieramy z puli wyciagnietych wynikow losowy wpis.
        Jezeli nie uwzgledniamy predkosci:
            Znajdujemy w historii wszystkie biegi mieszczace sie w preferencjach i ograniczamy wyniki do 30.
            Dobieramy z puli wyciagnietych wynikow losowy wpis.
        Wprowadzamy do MemcacheRunInfo historie biegu oraz pozostale dane dotyczace hosta.
        Wprowadzamy MemcachRunInfo do bazy oraz do memcache.
        Losujemy liczbe sekund do startu, od 30 do 300 po czym ja zwracamy.

        Aplikacja mobilna po otrzymaniu informacji rozpoczyna odliczanie natomiast dopiero wyswietla odliczanie od 10s, powyzej imituje poszukiwanie zawodnika.
         */
        if(!Validator.isPreferencesCorrect(runPreferences))
            throw new BadRequestException("Bad parameter format");

        DatastoreProfile datastoreProfile = Account.getDatastoreProfile(user);
        if(datastoreProfile == null)
            return null;

        Cleaner.cancelAllActiveUserRunProcesses(user, syncCache);

        CurrentRunInformation runInfo = Finder.findRandomOpponent(datastoreProfile, runPreferences);
        runInfo.setStarted(true);
        runInfo.setLastDatastoreSavedTime(new Date());
        ofy().save().entity(runInfo).now();

        CacheOrganizer.saveToCacheCurrentRunInformation(runInfo, datastoreProfile.getLogin(), syncCache);

        Random generator = new Random();
        int i = generator.nextInt(Constants.SECONDS_TO_START_RUN) + 30;
        return new RunStartInfo(runInfo.getDistance(), i);
    }

    /**
     * Funkcja wykorzystywana do zahostowania biegu ze znajomym.
     *
     * @param runPreferences - aspiration and reservation
     * @param friendsLogin -
     * @return true - everything went well, false - sth went wrong
     */
    @ApiMethod(name = "hostRunWithFriend", path = "hostRunWithFriend")
    public WrappedBoolean hostRunWithFriend(User user, RunPreferences runPreferences, @Named("friendsLogin") String friendsLogin) throws OAuthRequestException{
        //Sprawdzenie poprawnosci danych
            //czy preferencje sa poprawne
            //czy login znajomego jest poprawny
            //czy taki login istnieje
        //wyciagamy nasz profil z bazy
        //Anulujemy wszelkie biegi w jakich uczestniczymy, jesli sa w trakcie to z uwzglednieniem statystyk, jesli sa na etapie uzgadniania, tylko usuwamy.
        //Tworzymy wpis do bazy z wypelnionymi przez nas polami
        //zapisujemy wpis do memcache pod "runMatch:ourLogin"
        //zwracamy true
        if(!Validator.isPreferencesCorrect(runPreferences) || !Validator.isLoginCorrect(friendsLogin))
            return new WrappedBoolean(false);
        if(!Account.checkIfLoginExist(friendsLogin))
            return new WrappedBoolean(false);
        Profile ourProfile = signIn(user);
        if(ourProfile == null)
            return new WrappedBoolean(false);
        if(friendsLogin.equals(ourProfile.getLogin()))
            return new WrappedBoolean(false);
        Cleaner.cancelAllActiveUserRunProcesses(user, syncCache);
        RunMatcher runMatcher = new RunMatcher(ourProfile.getLogin(), friendsLogin, runPreferences);
        ofy().save().entity(runMatcher).now();
        syncCache.put("runMatch:".concat(ourProfile.getLogin()), runMatcher);
        return new WrappedBoolean(true);
    }

    /**
     * Funkcja sluzaca sprawdzeniu czy mamy sparowany wyscig.
     *
     * @return -1 - blad, 0 - jesli oczekujemy, >0 - czas do startu wyscigu
     */
    @ApiMethod(name = "startRunWithFriend", path = "startRunWithFriend")
    public RunStartInfo startRunWithFriend(User user) throws OAuthRequestException{
        //Sprawdzenie poprawnosci parametrow
        //wyciagamy nasz profil z bazy
        //sprawdzamy czy istnieje nasz bieg pod kluczem "runMatch:ourLogin"
            //jesli brak to probujemy wyciagnac z bazy - jesli brak zwracamy blad
            //jesli jest upewniamy sie ze to bieg z nami
        //jesli nie ma partnera to zwracamy 0
        //odczytujemy czas dolaczenia naszego partnera do biegu
        //usuwamy wpis dotyczacy parowania
        //tworzymy nowy wpis z biegiem pod kluczem currentRun:login
        //zwracamy czas do startu (bazowy pomniejszony o czas jaki uplynal od czasu dolaczenia przeciwnika do biegu)
        Profile ourProfile = signIn(user);
        if(ourProfile == null)
            return new RunStartInfo(-1,-1);
        Cleaner.cancelAllActiveUserRuns(user, syncCache);
        RunMatcher runMatcher = (RunMatcher) syncCache.get("runMatch:".concat(ourProfile.getLogin()));
        if(runMatcher == null) {
            Query<RunMatcher> query = ofy().load().type(RunMatcher.class).order("hostLogin");
            query = query.filter("hostLogin =",ourProfile.getLogin());
            runMatcher = query.first().now();
            if(runMatcher == null)
                return new RunStartInfo(-1,-1);
            syncCache.put("runMatch:".concat(ourProfile.getLogin()), runMatcher);
        }
        if(!runMatcher.isComplete())
            return new RunStartInfo(-1, 0);
        CurrentRunInformation currentRunInformation = new CurrentRunInformation();
        currentRunInformation.setHostLogin(runMatcher.getHostLogin());
        currentRunInformation.setOpponentLogin(runMatcher.getOpponentLogin());
        currentRunInformation.setRunWithRandom(false);
        currentRunInformation.setDistance(runMatcher.getDistance());
        currentRunInformation.setLastDatastoreSavedTime(new Date());
        currentRunInformation.setHostRunResultId();
        currentRunInformation.setOpponentRunResultId();
        Date acceptByOpponentDate = runMatcher.getAcceptByOpponentDate();
        RunResultDatastore hostRunResult = new RunResultDatastore();
        hostRunResult.setId(currentRunInformation.getHostRunResultId());
        RunResultDatastore opponentRunResult = new RunResultDatastore();
        opponentRunResult.setId(currentRunInformation.getOpponentRunResultId());
        ofy().delete().entity(runMatcher).now();
        ofy().save().entities(currentRunInformation, hostRunResult, opponentRunResult).now();
        syncCache.delete("runMatch:".concat(ourProfile.getLogin()));
        CacheOrganizer.saveToCacheCurrentRunInformation(currentRunInformation, ourProfile.getLogin(), syncCache);
        int d = (int) DateDifferenceCounter.getDateDiff(acceptByOpponentDate, new Date(), TimeUnit.SECONDS);
        return new RunStartInfo(currentRunInformation.getDistance(), Constants.SECONDS_TO_START_RUN-d);
    }

    /**
     * Funkcja sluzaca sprawdzeniu czy mamy sparowany wyscig.
     *
     * @param runPreferences - aspiration and reservation
     * @return -1 - blad, >0 - ustalony dystans
     */
    @ApiMethod(name = "joinRunWithFriend", path = "joinRunWithFriend")
    public RunStartInfo joinRunWithFriend(User user, RunPreferences runPreferences) throws OAuthRequestException{
        //Sprawdzenie poprawnosci parametrow
        //wyciagamy nasz profil z bazy
        //wyciagamy z bazy wpis gdzie widnieje nasz login jako opponent
        //probujemy wyciagnac z bazy - jesli brak zwracamy blad
        //jesli jest porownujemy preferencje i dobieramy dystans
        //uzupelniamy wyciagniety wpis, po czym zapisujemy go do bazy i do memcache pod "runwithfriend:login"
        //zwracamy ustalony dystans
        if(!Validator.isPreferencesCorrect(runPreferences))
            return new RunStartInfo(-1,-1);
        Profile ourProfile = signIn(user);
        if(ourProfile == null)
            return new RunStartInfo(-1,-1);
        Cleaner.cancelAllActiveUserRuns(user, syncCache);
        Query<RunMatcher> query = ofy().load().type(RunMatcher.class).order("opponentLogin");
        query = query.filter("opponentLogin =",ourProfile.getLogin());
        List<RunMatcher> runMatcherList = query.list();
        if(runMatcherList == null)
            return new RunStartInfo(-1,-2);
        RunMatcher newestRun = null;
        Iterator<RunMatcher> iterator = runMatcherList.iterator();
        while(iterator.hasNext()) {
            if (newestRun == null) {
                newestRun = iterator.next();
                continue;
            }
            RunMatcher r = iterator.next();
            if (newestRun.getCreateDate().before(r.getCreateDate()))
                newestRun = r;
        }
        //Sytuacja w ktorej nikt nam nie hostuje gry
        if(newestRun == null)
            return new RunStartInfo(-1,-3);
        runMatcherList.remove(newestRun);
        ofy().delete().entities(runMatcherList).now();
        int distance = Finder.findPerfectDistance(runPreferences, newestRun.getHostRunPreferences());
        if(distance < 0)
            return new RunStartInfo(-1,-4);
        newestRun.setDistance(distance);
        newestRun.setAcceptByOpponentDate(new Date());
        newestRun.setComplete(true);
        ofy().save().entity(newestRun).now();
        syncCache.put("runMatch:".concat(newestRun.getHostLogin()), newestRun);
        return new RunStartInfo(distance,Constants.SECONDS_TO_START_RUN);
    }

    /**
     * Funkcja sluzaca sprawdzeniu czy host nam sie nie rozlaczyl podczas parowania
     *
     * @return true jesli ok, false jesli host stracil polaczenie
     */
    @ApiMethod(name = "checkIfHostIsAlive", path = "checkIfHostIsAlive")
    public WrappedBoolean checkIfHostIsAlive(User user) throws OAuthRequestException{
        //wyciagamy nasz profil z bazy
        //sprawdzamy czy jest dla nas przygotowany wyscig
            //jesli brak to false
        //uzupelniamy go i zwracamy true

        //PO WYWOLANIU WE WPISIE TRZEBA USTAWIC FLAGE ZE ZOSTALO TO SPRAWDZONE BY WIEDZIEC ZE PO TYM FAKCIE NIE MA WYMOWEK CO DO STATYSTYK

        //sprawdzamy czy nasz RunMatcher jest w bazie caly czas.
        //jesli jest to sprawdzamy czy uplynelo 20sekund odkad go stworzylismy jako complete (complete musi byc, jesli nie to nie ma o czym mowic blad odrazu)
        //jesli minelo mniej niz 20s to blad
        //jesli wiecej to informacja ze host nam sie rozlaczyl i rozpoczynamy usuwanie wyscigu
        //jesli brak wpisu to upewniamy sie w bazie danych czy mamy juz bieg. Jesli mamy to zwracamy ze wszystko super, jesli nie to blad i czyscimy nasze dane.

        //sprawdzamy czy istnieje nasz profil
        //sprawdzamy czy juz jest stworzone CurrentRunState
        //tak:
            //sprawdzamy czy nasz RunMatcher usuniety z bazy i z memcache
            //tak:
                //sprawdzamy czy
                //zapisujemy do CurrentRuntRunStateflage ze biegniemy
            //nie:
                //nie powinno byc takiej sytuacji - zwracamy blad
        //nie:
            //sprawdzamy czy istnieje nasz RunMatcher - probujemy wyciagnac
            //istnieje mamy go:
                //sprawdzamy czy minelo wiecej niz 20 sekund odkad go wypelnilismy metoda joinRunWithFriend
                //nie lub brak daty wypelnienia:
                    //zwracamy blad
                //minelo wiecej:
                    //nasz host sie rozlaczyl - czyscimy caly bieg, zwracamy blad
             //nie istnieje:
                //zwracamy blad
        Profile ourProfile = signIn(user);
        if(ourProfile == null)
            return new WrappedBoolean(false);

        Query<CurrentRunInformation> query = ofy().load().type(CurrentRunInformation.class).order("opponentLogin");
        query = query.filter("opponentLogin =",ourProfile.getLogin());
        CurrentRunInformation currentRunInformation = query.first().now();
        if(currentRunInformation == null) {
            //sprawdzamy czy istnieje nasz RunMatcher - probujemy wyciagnac
            Query<RunMatcher> query2 = ofy().load().type(RunMatcher.class).order("opponentLogin");
            query2 = query2.filter("opponentLogin =",ourProfile.getLogin());
            RunMatcher runMatcher = query2.first().now();
            if(runMatcher == null)
                return new WrappedBoolean(false);
            if(DateDifferenceCounter.getDateDiff(runMatcher.getAcceptByOpponentDate(), new Date(), TimeUnit.SECONDS) > 20) {
                //host sie rozlaczyl
                Cleaner.cancelAllPairingProcesses(user, syncCache);
                return new WrappedBoolean(false);
            }
            return new WrappedBoolean(false);
        }
        if(currentRunInformation.getStarted())
            return new WrappedBoolean(false);
        Query<RunMatcher> query3 = ofy().load().type(RunMatcher.class).order("opponentLogin");
        query3 = query3.filter("opponentLogin =",ourProfile.getLogin());
        RunMatcher runMatcher = query3.first().now();
        if(runMatcher == null) {
            currentRunInformation.setStarted(true);
            ofy().save().entity(currentRunInformation);
            CacheOrganizer.saveToCacheCurrentRunInformation(currentRunInformation, ourProfile.getLogin(), syncCache);
            return new WrappedBoolean(true);
        }
        return new WrappedBoolean(false);
    }

    /**
     * Funkcja wykorzystywana do anulowania udzialu w biegu z uwzglednieniem statystyk
     * @return true jesli wszystko anulowalo sie poprawnie, false jezeli wystapil blad
     */
    @ApiMethod(name = "cancelRun", path = "cancelRun")
    public WrappedBoolean cancelRun(User user) throws OAuthRequestException{
        Cleaner.cancelAllActiveUserRunProcesses(user, syncCache);
        return new WrappedBoolean(true);
    }

    /**
     * Funkcja sluzaca do informowania o aktualnym postepie w biegu
     * @param runResult - lista wynikow jakie zapisalismy
     * @param forecast - czas w sekundach na ile w przod ma wybiec z obliczeniem serwer i zwrocic pozycje przeciwnika, jezeli aplikacja widzi ze regularnie serwer odpowiada srednio po 3 sekundach, powinna wpisac 3 sekundy, aby w momencie otrzymania informacji byla ona mozliwie najbardziej aktualna
     *
     * @return dystans przeciwnika jaki mial po odpowiadajacym czasie
     */
    @ApiMethod(name = "currentRunState", path = "currentRunState")
    public OpponentPositionInfo currentRunState(User user, RunResult runResult, @Named("forecast") int forecast) throws OAuthRequestException, BadRequestException{
        /*
        Opis dzialania:
        Sprawdzenie poprawnosci danych.
        Niepoprawne:
            Rzucamy wyjatek
        Wyciagniecie loginu uzytkownika z bazy.
        Sprawdzenie czy istnieje wpis w memcache pod loginem.
        Nie istnieje:
            Sprawdzenie czy istnieje w bazie:
            Nie istnieje:
                blad
            Istnieje:
                Sprawdzamy czy bieg z prawdziwym i czy to nasz bieg.
                Z prawdziwym:
                    Nie nasz:
                        Wyciagamy inny wpis.
                        //
                    Nasz:
                        //
                Z randomem:
                    Wyciagniecie wpisu uaktualnienie rezultatow i daty.
                    Zapisanie do memcache.
        Istnieje:
            Sprawdzamy czy bieg z prawdziwym i czy to nasz bieg.
            Z prawdziwym:
                Nie nasz:
                    Wyciagamy inny wpis.
                    //
                Nasz:
                    //
            Z randomem:
                Uaktualnienie rezultatow oraz sprawdzenie daty zapisu.
                Dawno:
                    Zapis do bazu.
        Na podstawie rezultatow przeciwnika i informacji o czasie przewidywania obliczamy pozycje przeciwnika.
        Zwracamy pozycje przeciwnika.
         */
        if(!Validator.isForecastCorrect(forecast) || !Validator.isRunResultCorrect(runResult))
            throw new BadRequestException("Bad parameter format");

        Profile p = Account.getUserProfile(user, syncCache);
        if(p == null)
            return null;

        CurrentRunInformation currentRunInformation = CacheOrganizer.getFromCacheCurrentRunInforamtion(p.getLogin(), syncCache);
        if(currentRunInformation == null) {
            currentRunInformation = Finder.findNotFinishedRunForHost(p.getLogin());
            if(currentRunInformation == null) {
                currentRunInformation = Finder.findNotFinishedRunForOpponent(p.getLogin());
                if(currentRunInformation == null)
                        return null;
                CacheOrganizer.saveToCacheCurrentRunInformation(currentRunInformation, p.getLogin(), syncCache);
            }
        }
        if(currentRunInformation.isRunWithRandom()){
            //uaktualnienie wpisu
            //sprawdzenie wygranej/przegranej
            //zapis do memcache oraz jesli dawno to do bazy
            //przewidzenie pozycji przeciwnika
            RunResult hostRunResult = currentRunInformation.getHostRunResult();
            if(hostRunResult == null)
                hostRunResult = new RunResult(runResult.getResults());
            else {
                RunResultPiece hostLastRunResultPiece = hostRunResult.getResults().get(hostRunResult.getResults().size()-1);
                RunResultPiece parameterFirstRunResultPiece = runResult.getResults().get(0);
                if(hostLastRunResultPiece.getTime() >= parameterFirstRunResultPiece.getTime())
                    throw new BadRequestException("Run results are redundant");
                if(hostLastRunResultPiece.getDistance() > parameterFirstRunResultPiece.getDistance())
                    throw new BadRequestException("Run results are wrong");
                hostRunResult.addResults(runResult.getResults());
            }
            currentRunInformation.setHostRunResult(hostRunResult);

            int winner = Analyst.checkWhoIsTheWinner(hostRunResult, currentRunInformation.getOpponentRunResult(), currentRunInformation.getDistance(), true, true);

            if(winner == 0) {
                if(new Date().getTime() - currentRunInformation.getLastDatastoreSavedTime().getTime() > Constants.ACCEPTED_TIME_IN_SECONDS_WITHOUT_RUN_RESULT_SAVING_TO_DATASTORE) {
                    currentRunInformation.setLastDatastoreSavedTime(new Date());
                    ofy().save().entity(currentRunInformation).now();
                }
            } else {
                currentRunInformation.setLastDatastoreSavedTime(new Date());
                ofy().save().entity(currentRunInformation).now();
            }
            CacheOrganizer.saveToCacheCurrentRunInformation(currentRunInformation, p.getLogin(), syncCache);

            if(winner == 2) {
                //przegrana
                DatastoreProfile datastoreProfile = Account.getDatastoreProfile(user);
                if(datastoreProfile == null)
                    return null;
                //dodajemy statystyki
                datastoreProfile.addLoseRace();
                //dodajemy historie uzytkownika
                DatastoreProfileHistory profileHistory = new DatastoreProfileHistory();
                profileHistory.setParent(Key.create(DatastoreProfile.class, user.getEmail()));
                RunResultPiece lastHostResult = hostRunResult.getResults().get(hostRunResult.getResults().size()-1);
                float avgSpeed = Analyst.countAvgSpeed((float) lastHostResult.getDistance(), (float) lastHostResult.getTime());
                profileHistory.setAverageSpeed(avgSpeed);
                profileHistory.setTotalDistance(lastHostResult.getDistance());
                profileHistory.setDateOfRun(new Date());
                profileHistory.setIsWinner(false);
                profileHistory.setRunResult(currentRunInformation.getHostRunResult());
                //dodajemy historie ogolna
                DatastoreTotalHistory totalHistory = new DatastoreTotalHistory();
                totalHistory.setAverageSpeed(avgSpeed);
                totalHistory.setTotalDistance(lastHostResult.getDistance());
                totalHistory.setRunResult(currentRunInformation.getHostRunResult());
                //zapisujemy do bazy
                ofy().save().entities(datastoreProfile, profileHistory, totalHistory).now();
                CacheOrganizer.removeFromCacheWholeRunInformation(p.getLogin(), syncCache);
                ofy().delete().entity(currentRunInformation);
                return new OpponentPositionInfo(-1, 0);
            }

            if (winner == 1) {
                //wygrana
                DatastoreProfile datastoreProfile = Account.getDatastoreProfile(user);
                if (datastoreProfile == null)
                    return null;
                //dodajemy statystyki
                datastoreProfile.addWinRace();
                //dodajemy historie uzytkownika
                DatastoreProfileHistory profileHistory = new DatastoreProfileHistory();
                profileHistory.setParent(Key.create(DatastoreProfile.class, user.getEmail()));
                RunResultPiece lastHostResult = hostRunResult.getResults().get(hostRunResult.getResults().size()-1);
                float avgSpeed = Analyst.countAvgSpeed((float) lastHostResult.getDistance(), (float) lastHostResult.getTime());
                profileHistory.setAverageSpeed(avgSpeed);
                profileHistory.setTotalDistance(lastHostResult.getDistance());
                profileHistory.setDateOfRun(new Date());
                profileHistory.setIsWinner(true);
                profileHistory.setRunResult(currentRunInformation.getHostRunResult());
                //dodajemy historie ogolna
                DatastoreTotalHistory totalHistory = new DatastoreTotalHistory();
                totalHistory.setAverageSpeed(avgSpeed);
                totalHistory.setTotalDistance(lastHostResult.getDistance());
                totalHistory.setRunResult(currentRunInformation.getHostRunResult());
                //zapisujemy do bazy
                ofy().save().entities(datastoreProfile, profileHistory, totalHistory).now();
                CacheOrganizer.removeFromCacheWholeRunInformation(p.getLogin(), syncCache);
                ofy().delete().entity(currentRunInformation);
                return new OpponentPositionInfo(-1, 1);
            }
            return Analyst.makePrediction(currentRunInformation, true, forecast, syncCache);
        } else {
            //sprawdzenie czy wyscig wystartowal
            //sprawdzenie czy mamy do czynienia z hostem
            //uaktualnienie wpisu
            //sprawdzenie wygranej/przegranej
            //zapis do memcache oraz jesli dawno to do bazy
            //przewidzenie pozycji przeciwnika
            if (!currentRunInformation.getStarted()) {
                return new OpponentPositionInfo(-1, -2);
            }

            boolean isHost = currentRunInformation.getHostLogin().equals(p.getLogin());

            RunResult playerRunResult;

            Key<RunResultDatastore> hostKey = Key.create(RunResultDatastore.class, currentRunInformation.getHostRunResultId());
            Key<RunResultDatastore> opponentKey = Key.create(RunResultDatastore.class, currentRunInformation.getOpponentRunResultId());

            //proba wziecia wpisow z memcache
            Map<Key<RunResultDatastore>, RunResultDatastore> resultRunMap = new Map<Key<RunResultDatastore>, RunResultDatastore>() {
                @Override
                public int size() {
                    return 0;
                }

                @Override
                public boolean isEmpty() {
                    return false;
                }

                @Override
                public boolean containsKey(Object key) {
                    return false;
                }

                @Override
                public boolean containsValue(Object value) {
                    return false;
                }

                @Override
                public RunResultDatastore get(Object key) {
                    return null;
                }

                @Override
                public RunResultDatastore put(Key<RunResultDatastore> key, RunResultDatastore value) {
                    return null;
                }

                @Override
                public RunResultDatastore remove(Object key) {
                    return null;
                }

                @Override
                public void putAll(Map<? extends Key<RunResultDatastore>, ? extends RunResultDatastore> m) {

                }

                @Override
                public void clear() {

                }

                @Override
                public Set<Key<RunResultDatastore>> keySet() {
                    return null;
                }

                @Override
                public Collection<RunResultDatastore> values() {
                    return null;
                }

                @Override
                public Set<Entry<Key<RunResultDatastore>, RunResultDatastore>> entrySet() {
                    return null;
                }
            };

            RunResultDatastore runResultPlayer;
            RunResultDatastore runResultHost = CacheOrganizer.getFromCacheRunResultDatastore(currentRunInformation.getHostLogin(), syncCache);
            RunResultDatastore runResultOpponent = CacheOrganizer.getFromCacheRunResultDatastore(currentRunInformation.getOpponentLogin(), syncCache);
            if(runResultHost == null) {
                runResultHost = ofy().load().key(hostKey).now();
                CacheOrganizer.saveToCacheRunResultFromDatastore(currentRunInformation, syncCache);
            }
            if(runResultOpponent == null) {
                runResultOpponent = ofy().load().key(opponentKey).now();
                CacheOrganizer.saveToCacheRunResultFromDatastore(currentRunInformation, syncCache);
            }
            if(isHost) {
                runResultPlayer = runResultHost;
                playerRunResult = runResultHost.getRunResult();
            } else {
                runResultPlayer = runResultOpponent;
                playerRunResult = runResultOpponent.getRunResult();
            }

            if(playerRunResult == null)
                playerRunResult = new RunResult(runResult.getResults());
            else {
                RunResultPiece playerLastRunResultPiece = playerRunResult.getResults().get(playerRunResult.getResults().size()-1);
                RunResultPiece parameterFirstRunResultPiece = runResult.getResults().get(0);
                if(playerLastRunResultPiece.getTime() >= parameterFirstRunResultPiece.getTime())
                    throw new BadRequestException("Run results are redundant");
                if(playerLastRunResultPiece.getDistance() > parameterFirstRunResultPiece.getDistance())
                    throw new BadRequestException("Run results are wrong");
                playerRunResult.addResults(runResult.getResults());
            }

            runResultPlayer.setRunResult(playerRunResult);

            if(currentRunInformation.isWinnerExist()) {
                runResultPlayer.setLastDatastoreSavedTime(new Date());
                ofy().save().entity(runResultPlayer).now();
            } else {
                if (new Date().getTime() - runResultPlayer.getLastDatastoreSavedTime().getTime() > Constants.ACCEPTED_TIME_IN_SECONDS_WITHOUT_RUN_RESULT_SAVING_TO_DATASTORE) {
                    runResultPlayer.setLastDatastoreSavedTime(new Date());
                    ofy().save().entity(runResultPlayer).now();
                }
            }
            CacheOrganizer.saveToCacheRunResult(runResultPlayer, p.getLogin(), syncCache);

            if(currentRunInformation.isWinnerExist()) {
                //dodanie statystyk
                //zapisanie do historii swoich wynikow
                //zapisanie wynikow do historii ogolnej
                //usuniecie wynikow biegu
                //usuniecie wpisu ogolnego o biegu
                //zapisanie zmian w bazie danych
                DatastoreProfile datastoreProfile = Account.getDatastoreProfile(user);
                if (datastoreProfile == null)
                    return null;
                datastoreProfile.addLoseRace();
                //dodajemy historie uzytkownika
                DatastoreProfileHistory profileHistory = new DatastoreProfileHistory();
                profileHistory.setParent(Key.create(DatastoreProfile.class, user.getEmail()));

                RunResultPiece lastPlayerResult = runResultPlayer.getRunResult().getResults().get(runResultPlayer.getRunResult().getResults().size()-1);
                float avgSpeed = Analyst.countAvgSpeed((float) lastPlayerResult.getDistance(), (float) lastPlayerResult.getTime());
                profileHistory.setAverageSpeed(avgSpeed);
                profileHistory.setTotalDistance(lastPlayerResult.getDistance());
                profileHistory.setDateOfRun(new Date());
                profileHistory.setIsWinner(false);
                profileHistory.setRunResult(runResultPlayer.getRunResult());
                //dodajemy historie ogolna
                DatastoreTotalHistory totalHistory = new DatastoreTotalHistory();
                totalHistory.setAverageSpeed(avgSpeed);
                totalHistory.setTotalDistance(lastPlayerResult.getDistance());
                totalHistory.setRunResult(runResultPlayer.getRunResult());
                //zapisujemy do bazy
                ofy().save().entities(datastoreProfile, profileHistory, totalHistory).now();
                ofy().delete().entities(currentRunInformation, runResultPlayer);
                CacheOrganizer.removeFromCacheWholeRunInformation(currentRunInformation, syncCache);
                return new OpponentPositionInfo(-1, 0);
            }

            int winner;
            if(isHost)
                winner = Analyst.checkWhoIsTheWinner(runResultHost.getRunResult(), runResultOpponent.getRunResult(), currentRunInformation.getDistance(), false, true);
            else
                winner = Analyst.checkWhoIsTheWinner(runResultHost.getRunResult(), runResultOpponent.getRunResult(), currentRunInformation.getDistance(), false, false);

            if (winner == 1) {
                //wygrana
                DatastoreProfile datastoreProfile = Account.getDatastoreProfile(user);
                if (datastoreProfile == null)
                    return null;
                //dodajemy statystyki
                datastoreProfile.addWinRace();
                //dodajemy historie uzytkownika
                DatastoreProfileHistory profileHistory = new DatastoreProfileHistory();
                profileHistory.setParent(Key.create(DatastoreProfile.class, user.getEmail()));
                RunResultPiece lastResult = playerRunResult.getResults().get(playerRunResult.getResults().size()-1);
                float avgSpeed = Analyst.countAvgSpeed((float) lastResult.getDistance(), (float) lastResult.getTime());
                profileHistory.setAverageSpeed(avgSpeed);
                profileHistory.setTotalDistance(lastResult.getDistance());
                profileHistory.setDateOfRun(new Date());
                profileHistory.setIsWinner(true);
                profileHistory.setRunResult(playerRunResult);
                //dodajemy historie ogolna
                DatastoreTotalHistory totalHistory = new DatastoreTotalHistory();
                totalHistory.setAverageSpeed(avgSpeed);
                totalHistory.setTotalDistance(lastResult.getDistance());
                totalHistory.setRunResult(playerRunResult);
                //zapisujemy do bazy
                currentRunInformation.setWinnerExist(true);
                if(isHost)
                    currentRunInformation.setHostLogin("");
                else
                    currentRunInformation.setOpponentLogin("");
                ofy().save().entities(datastoreProfile, profileHistory, totalHistory, currentRunInformation).now();
                if(isHost)
                    CacheOrganizer.removeFromCacheWholeRunInformationForPlayerAndBasicInfoForOpponent(p.getLogin(), currentRunInformation.getOpponentLogin(), syncCache);
                else
                    CacheOrganizer.removeFromCacheWholeRunInformationForPlayerAndBasicInfoForOpponent(p.getLogin(), currentRunInformation.getHostLogin(), syncCache);
                ofy().delete().entity(runResultPlayer).now();
                return new OpponentPositionInfo(-1, 1);
            }
            return Analyst.makePrediction(currentRunInformation, isHost, forecast, syncCache);
        }
    }
}
