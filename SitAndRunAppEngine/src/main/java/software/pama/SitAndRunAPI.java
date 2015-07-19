package software.pama;


import static software.pama.utils.OfyService.ofy;

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
import software.pama.run.MemcacheRunInfo;
import software.pama.run.datastore.CurrentRunInformation;
import software.pama.run.RunResult;
import software.pama.run.RunResultPiece;
import software.pama.run.datastore.RunResultDatastore;
import software.pama.run.friend.RunMatcher;
import software.pama.users.datastore.DatastoreProfile;
import software.pama.users.datastore.DatastoreProfileHistory;
import software.pama.users.datastore.DatastoreTotalHistory;
import software.pama.users.Profile;
import software.pama.utils.Constants;
import software.pama.utils.DateHelper;
import software.pama.utils.Preferences;
import software.pama.utils.RunStartInfo;
import software.pama.validation.Validator;
import software.pama.wrapped.WrappedBoolean;

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
        return getUserProfile(user);
    }

    /**
     * Funkcja wykorzystywana do zakladania nowego konta.
     *
     * @param login nazwa uzytkownika jaka chcemy wykorzystywac (a-z, 0-9)
     * @return null jesli nazwa jest zajeta, profile jesli udalo sie zalozyc konto.
     *
     * @throws BadRequestException jesli przekazany login nie spelnia regul
     */
    @ApiMethod(name = "signUp", path = "signUp")
    public Profile signUp(User user, @Named("login") String login) throws OAuthRequestException, BadRequestException{
        /*
        Opis dzialania:
        Sprawdzamy czy przekazane parametry sa poprawne.
        Nie: rzucamy wyjatkiem BadReguestException
        Tak:
        Sprawdzamy czy uzytkownik jest nowy - wywolujac signIn(), ktore zwraca
        Profil != null:
            Sytuacja bledna z zalozeniem dzialania aplikacji mobilnej,
            ktora powinna w pierwszej kolejnosci probowac sie zalogowac przy uzyciu signIn() i tylko w sytuacji,
            gdy signIn() zwrocilo null pozwolic na wywolanie signUp().
        null (oznacza to, ze uzytkownik jest nowy):
            Sprawdzamy czy przekazany login jest rozny od null oraz czy jest zgodny z konwencja znakow.
            Nie:
                Zwracamy specyficzny blad
            Tak:
                Przeszukujemy baze sprawdzajac czy przypadkiem podany login nie jest juz zajety.
                Jest zajety:
                    Zwracamy blad informujacy o zajetym loginie
                Jest wolny:
                    Tworzymy nowy profil.
                    Zapisujemy w bazie.
                    Wrzucamy profil do Memcache.
         */
        login = login.toLowerCase();
        if(!Validator.isLoginCorrect(login))
            throw new BadRequestException("Bad parameter format");
        if(checkIfLoginExist(login))
            return null;
        DatastoreProfile datastoreProfile = new DatastoreProfile(user.getEmail(), login);
        ofy().save().entity(datastoreProfile).now();
        Profile p = new Profile(login);
        syncCache.put(user.getEmail(), p);
        return p;
    }

    /**
     * Funkcja wykorzystywana do usuniecia konta.
     *
     * @return false jesli blad
     */
    @ApiMethod(name = "deleteAccount", path = "deleteAccount")
    public WrappedBoolean deleteAccount(User user) throws OAuthRequestException{
        cancelAllActiveUserRunProcesses(user);
        Key key = Key.create(DatastoreProfile.class, user.getEmail());
        ofy().delete().keys(ofy().load().ancestor(key).keys().list());
        ofy().delete().key(key).now();
        syncCache.delete(user.getEmail());
        return new WrappedBoolean(true);
    }

    /**
     * Funkcja wykorzystywana do zasygnalizowania, ze jestesmy gotowi do biegu z randomowa osoba.
     *
     * @param preferences - aspiracja oraz rezerwacja
     * @return 0 jesli oczekuje, > 0 start wyscigu liczba oznacza ilosc sekund do odliczenia, < 0 jesli blad
     */
    @ApiMethod(name = "startRunWithRandom", path = "startRunWithRandom")
    public RunStartInfo startRunWithRandom(User user, Preferences preferences) throws OAuthRequestException, BadRequestException{
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
        if(!Validator.isPreferencesCorrect(preferences))
            throw new BadRequestException("Bad parameter format");

        DatastoreProfile datastoreProfile = getDatastoreProfile(user);
        if(datastoreProfile == null)
            return null;

        cancelAllActiveUserRunProcesses(user);

        CurrentRunInformation runInfo = findRandomOpponent(datastoreProfile, preferences);
        runInfo.setStarted(true);
        runInfo.setLastDatastoreSavedTime(new Date());
        ofy().save().entity(runInfo).now();

        //syncCache.put(runInfo.getHostLogin(), runInfo);

        //syncCache.put("runWithRandom:".concat(runInfo.getHostLogin()), runInfo);
        //syncCache.put(runInfo.getHostLogin(), new MemcacheRunInfo(true, runInfo.getHostLogin()));

        saveToCacheCurrentRunInformation(runInfo, datastoreProfile.getLogin());

        Random generator = new Random();
        int i = generator.nextInt(30) + 30;
        return new RunStartInfo(runInfo.getDistance(), i);
    }

    /**
     * Funkcja wykorzystywana do zahostowania biegu ze znajomym.
     *
     * @param preferences - aspiration and reservation
     * @param friendsLogin -
     * @return true - everything went well, false - sth went wrong
     */
    @ApiMethod(name = "hostRunWithFriend", path = "hostRunWithFriend")
    public WrappedBoolean hostRunWithFriend(User user, Preferences preferences, @Named("friendsLogin") String friendsLogin) throws OAuthRequestException{
        //Sprawdzenie poprawnosci danych
            //czy preferencje sa poprawne
            //czy login znajomego jest poprawny
            //czy taki login istnieje
        //wyciagamy nasz profil z bazy
        //Anulujemy wszelkie biegi w jakich uczestniczymy, jesli sa w trakcie to z uwzglednieniem statystyk, jesli sa na etapie uzgadniania, tylko usuwamy.
        //Tworzymy wpis do bazy z wypelnionymi przez nas polami
        //zapisujemy wpis do memcache pod "runMatch:ourLogin"
        //zwracamy true
        if(!Validator.isPreferencesCorrect(preferences) || !Validator.isLoginCorrect(friendsLogin))
            return new WrappedBoolean(false);
        if(!checkIfLoginExist(friendsLogin))
            return new WrappedBoolean(false);
        Profile ourProfile = signIn(user);
        if(ourProfile == null)
            return new WrappedBoolean(false);
        if(friendsLogin.equals(ourProfile.getLogin()))
            return new WrappedBoolean(false);
        cancelAllActiveUserRunProcesses(user);
        RunMatcher runMatcher = new RunMatcher(ourProfile.getLogin(), friendsLogin, preferences);
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
        cancelAllActiveUserRuns(user);
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
        //syncCache.put(ourProfile.getLogin(), currentRunInformation);
        saveToCacheCurrentRunInformation(currentRunInformation, ourProfile.getLogin());
        int d = (int) DateHelper.getDateDiff(acceptByOpponentDate, new Date(), TimeUnit.SECONDS);
        return new RunStartInfo(currentRunInformation.getDistance(), 40-d);
    }

    /**
     * Funkcja sluzaca sprawdzeniu czy mamy sparowany wyscig.
     *
     * @param preferences - aspiration and reservation
     * @return -1 - blad, >0 - ustalony dystans
     */
    @ApiMethod(name = "joinRunWithFriend", path = "joinRunWithFriend")
    public RunStartInfo joinRunWithFriend(User user, Preferences preferences) throws OAuthRequestException{
        //Sprawdzenie poprawnosci parametrow
        //wyciagamy nasz profil z bazy
        //wyciagamy z bazy wpis gdzie widnieje nasz login jako opponent
        //probujemy wyciagnac z bazy - jesli brak zwracamy blad
        //jesli jest porownujemy preferencje i dobieramy dystans
        //uzupelniamy wyciagniety wpis, po czym zapisujemy go do bazy i do memcache pod "runwithfriend:login"
        //zwracamy ustalony dystans
        if(!Validator.isPreferencesCorrect(preferences))
            return new RunStartInfo(-1,-1);
        Profile ourProfile = signIn(user);
        if(ourProfile == null)
            return new RunStartInfo(-1,-1);
        cancelAllActiveUserRuns(user);
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
        int distance = countDistance(preferences, newestRun.getHostPreferences());
        if(distance < 0)
            return new RunStartInfo(-1,-4);
        newestRun.setDistance(distance);
        newestRun.setAcceptByOpponentDate(new Date());
        newestRun.setComplete(true);
        ofy().save().entity(newestRun).now();
        syncCache.put("runMatch:".concat(newestRun.getHostLogin()), newestRun);
        //syncCache.put("whoIsHostInMyRun:".concat(ourProfile.getLogin()), newestRun.getHostLogin());
        return new RunStartInfo(distance,40);
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
            if(DateHelper.getDateDiff(runMatcher.getAcceptByOpponentDate(), new Date(), TimeUnit.SECONDS) > 20) {
                //host sie rozlaczyl
                cancelAllPairingProcesses(user);
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
            //syncCache.put(currentRunInformation.getHostLogin(), currentRunInformation);
            //syncCache.put(ourProfile.getLogin(), "whoIsHostInMyRun:".concat(currentRunInformation.getHostLogin()));
            ofy().save().entity(currentRunInformation);
            saveToCacheCurrentRunInformation(currentRunInformation, ourProfile.getLogin());
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
        return new WrappedBoolean(cancelAllActiveUserRunProcesses(user));
    }

    /**
     * Funkcja sluzaca do informowania o aktualnym postepie w biegu
     * @param runResult - lista wynikow jakie zapisalismy
     * @param forecast - czas w sekundach na ile w przod ma wybiec z obliczeniem serwer i zwrocic pozycje przeciwnika, jezeli aplikacja widzi ze regularnie serwer odpowiada srednio po 3 sekundach, powinna wpisac 3 sekundy, aby w momencie otrzymania informacji byla ona mozliwie najbardziej aktualna
     *
     * @return dystans przeciwnika jaki mial po odpowiadajacym czasie
     */
    @ApiMethod(name = "currentRunState", path = "currentRunState")
    public RunResultPiece currentRunState(User user, RunResult runResult, @Named("forecast") int forecast) throws OAuthRequestException, BadRequestException{
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

        Profile p = getUserProfile(user);
        if(p == null)
            return null;

        CurrentRunInformation currentRunInformation = getFromCacheCurrentRunInforamtion(p.getLogin());
        if(currentRunInformation == null) {
            currentRunInformation = getNotFinishedRunForHost(p.getLogin());
            if(currentRunInformation == null) {
                currentRunInformation = getNotFinishedRunForOpponent(p.getLogin());
                if(currentRunInformation == null)
                        return null;
                saveToCacheCurrentRunInformation(currentRunInformation, p.getLogin());
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

            int winner = checkWhoIsTheWinner(hostRunResult, currentRunInformation.getOpponentRunResult(), currentRunInformation.getDistance(), true, true);

            if(winner == 0) {
                if(new Date().getTime() - currentRunInformation.getLastDatastoreSavedTime().getTime() > 1000*30) {
                    currentRunInformation.setLastDatastoreSavedTime(new Date());
                    ofy().save().entity(currentRunInformation).now();
                }
            } else {
                currentRunInformation.setLastDatastoreSavedTime(new Date());
                ofy().save().entity(currentRunInformation).now();
            }
            saveToCacheCurrentRunInformation(currentRunInformation, p.getLogin());

            if(winner == 2) {
                //przegrana
                DatastoreProfile datastoreProfile = getDatastoreProfile(user);
                if(datastoreProfile == null)
                    return null;
                //dodajemy statystyki
                datastoreProfile.addLoseRace();
                //dodajemy historie uzytkownika
                DatastoreProfileHistory profileHistory = new DatastoreProfileHistory();
                profileHistory.setParent(Key.create(DatastoreProfile.class, user.getEmail()));
                RunResultPiece lastHostResult = hostRunResult.getResults().get(hostRunResult.getResults().size()-1);
                float avgSpeed = countAvgSpeed((float)lastHostResult.getDistance(), (float)lastHostResult.getTime());
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
                //syncCache.delete(p.getLogin());
                removeFromCacheWholeRunInformation(p.getLogin());
                ofy().delete().entity(currentRunInformation);
                return new RunResultPiece(-1, 0);
            }

            if (winner == 1) {
                //wygrana
                DatastoreProfile datastoreProfile = getDatastoreProfile(user);
                if (datastoreProfile == null)
                    return null;
                //dodajemy statystyki
                datastoreProfile.addWinRace();
                //dodajemy historie uzytkownika
                DatastoreProfileHistory profileHistory = new DatastoreProfileHistory();
                profileHistory.setParent(Key.create(DatastoreProfile.class, user.getEmail()));
                RunResultPiece lastHostResult = hostRunResult.getResults().get(hostRunResult.getResults().size()-1);
                float avgSpeed = countAvgSpeed((float)lastHostResult.getDistance(), (float)lastHostResult.getTime());
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
                //syncCache.delete(p.getLogin());
                removeFromCacheWholeRunInformation(p.getLogin());
                ofy().delete().entity(currentRunInformation);
                return new RunResultPiece(-1, 1);
            }

            //syncCache.put(p.getLogin(), currentRunInformation);


            return makePrediction(currentRunInformation, true, forecast);
        } else {
            //sprawdzenie czy wyscig wystartowal
            //sprawdzenie czy mamy do czynienia z hostem
            //uaktualnienie wpisu
            //sprawdzenie wygranej/przegranej
            //zapis do memcache oraz jesli dawno to do bazy
            //przewidzenie pozycji przeciwnika
            if (!currentRunInformation.getStarted()) {
                return new RunResultPiece(-1, -2);
            }

            boolean isHost = currentRunInformation.getHostLogin().equals(p.getLogin());

            RunResult playerRunResult;

            Key<RunResultDatastore> playerKey;
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
            //TODO uzycie memcache
            /*
            RunResultDatastore hostRunResultDatastore = (RunResultDatastore) syncCache.get("RunResult:".concat(hostKey.toString()));
            RunResultDatastore oppponentRunResultDatastore = (RunResultDatastore) syncCache.get("RunResult:".concat(opponentKey.toString()));

            if(hostRunResultDatastore == null && oppponentRunResultDatastore == null) {
                resultRunMap = ofy().load().keys(hostKey, opponentKey);
            } else if (hostRunResultDatastore == null) {
                RunResultDatastore runResultDatastore = (RunResultDatastore) ofy().load().key(hostKey).now();
                resultRunMap.put(hostKey, runResultDatastore);
                resultRunMap.put(opponentKey, oppponentRunResultDatastore);
            } else if (oppponentRunResultDatastore == null) {
                RunResultDatastore runResultDatastore = (RunResultDatastore) ofy().load().key(opponentKey).now();
                resultRunMap.put(hostKey, hostRunResultDatastore);
                resultRunMap.put(opponentKey, runResultDatastore);
            } else {
                resultRunMap.put(hostKey, hostRunResultDatastore);
                resultRunMap.put(opponentKey, oppponentRunResultDatastore);
            }
            */

            //TODO sprawdzic czy ponizsze linie dzialaja poprawnie, jesli tak to przeniesc schemat do makePrediction

            RunResultDatastore runResultPlayer;
            RunResultDatastore runResultHost = getFromCacheRunResultDatastore(currentRunInformation.getHostLogin());
            RunResultDatastore runResultOpponent = getFromCacheRunResultDatastore(currentRunInformation.getOpponentLogin());
            if(runResultHost == null) {
                runResultHost = (RunResultDatastore) ofy().load().key(hostKey).now();
                saveToCacheRunResultFromDatastore(currentRunInformation);
            }
            if(runResultOpponent == null) {
                runResultOpponent = (RunResultDatastore) ofy().load().key(opponentKey).now();
                saveToCacheRunResultFromDatastore(currentRunInformation);
            }
            //resultRunMap.put(hostKey, runResultHost);
            //resultRunMap.put(opponentKey, runResultOpponent);

            /*
            resultRunMap = ofy().load().keys(hostKey, opponentKey);
            if(isHost) {
                playerKey = hostKey;
                playerRunResult = resultRunMap.get(hostKey).getRunResult();
            } else {
                playerKey = opponentKey;
                playerRunResult = resultRunMap.get(opponentKey).getRunResult();
            }
            */
            //resultRunMap = ofy().load().keys(hostKey, opponentKey);
            //TODO zmiana z gory na dol
            if(isHost) {
                //playerKey = hostKey;
                runResultPlayer = runResultHost;
                playerRunResult = runResultHost.getRunResult();
            } else {
                //playerKey = opponentKey;
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

            //resultRunMap.get(playerKey).setRunResult(playerRunResult);
            //ofy().save().entity(resultRunMap.get(playerKey)).now();
            //saveToCacheRunResult(resultRunMap.get(playerKey), p.getLogin());
            //TODO zmiana z gory na dol
            runResultPlayer.setRunResult(playerRunResult);
            //ofy().save().entity(runResultPlayer).now();

            if(currentRunInformation.isWinnerExist()) {
                runResultPlayer.setLastDatastoreSavedTime(new Date());
                ofy().save().entity(runResultPlayer).now();
            } else {
                if (new Date().getTime() - runResultPlayer.getLastDatastoreSavedTime().getTime() > 1000 * 30) {
                    runResultPlayer.setLastDatastoreSavedTime(new Date());
                    ofy().save().entity(runResultPlayer).now();
                }
            }
            saveToCacheRunResult(runResultPlayer, p.getLogin());

            if(currentRunInformation.isWinnerExist()) {
                //dodanie statystyk
                //zapisanie do historii swoich wynikow
                //zapisanie wynikow do historii ogolnej
                //usuniecie wynikow biegu
                //usuniecie wpisu ogolnego o biegu
                //zapisanie zmian w bazie danych
                DatastoreProfile datastoreProfile = getDatastoreProfile(user);
                if (datastoreProfile == null)
                    return null;
                datastoreProfile.addLoseRace();
                //dodajemy historie uzytkownika
                DatastoreProfileHistory profileHistory = new DatastoreProfileHistory();
                profileHistory.setParent(Key.create(DatastoreProfile.class, user.getEmail()));

                //RunResultPiece lastPlayerResult = resultRunMap.get(playerKey).getRunResult().getResults().get(resultRunMap.get(playerKey).getRunResult().getResults().size()-1);
                //TODO zmiana z gory na dol
                RunResultPiece lastPlayerResult = runResultPlayer.getRunResult().getResults().get(runResultPlayer.getRunResult().getResults().size()-1);
                float avgSpeed = countAvgSpeed((float)lastPlayerResult.getDistance(), (float)lastPlayerResult.getTime());
                profileHistory.setAverageSpeed(avgSpeed);
                profileHistory.setTotalDistance(lastPlayerResult.getDistance());
                profileHistory.setDateOfRun(new Date());
                profileHistory.setIsWinner(false);
                //profileHistory.setRunResult(resultRunMap.get(playerKey).getRunResult());
                //TODO zmiana z gory na dol
                profileHistory.setRunResult(runResultPlayer.getRunResult());
                //dodajemy historie ogolna
                DatastoreTotalHistory totalHistory = new DatastoreTotalHistory();
                totalHistory.setAverageSpeed(avgSpeed);
                totalHistory.setTotalDistance(lastPlayerResult.getDistance());
                //totalHistory.setRunResult(resultRunMap.get(playerKey).getRunResult());
                //TODO zmiana z gory na dol
                totalHistory.setRunResult(runResultPlayer.getRunResult());
                //zapisujemy do bazy
                ofy().save().entities(datastoreProfile, profileHistory, totalHistory).now();
                //ofy().delete().entities(currentRunInformation, resultRunMap.get(playerKey));
                //TODO zmiana z gory na dol
                ofy().delete().entities(currentRunInformation, runResultPlayer);
                removeFromCacheWholeRunInformation(currentRunInformation);
                //if(isHost)
                    //syncCache.delete(p.getLogin());
                //else
                    //syncCache.delete("whoIsHostInMyRun:".concat(p.getLogin()));
                return new RunResultPiece(-1, 0);
            }

            int winner;
            /*
            if(isHost)
                winner = checkWhoIsTheWinner(resultRunMap.get(hostKey).getRunResult(), resultRunMap.get(opponentKey).getRunResult(), currentRunInformation.getDistance(), false, true);
            else
                winner = checkWhoIsTheWinner(resultRunMap.get(hostKey).getRunResult(), resultRunMap.get(opponentKey).getRunResult(), currentRunInformation.getDistance(), false, false);
            */
            //TODO zmiana z gory na dol
            if(isHost)
                winner = checkWhoIsTheWinner(runResultHost.getRunResult(), runResultOpponent.getRunResult(), currentRunInformation.getDistance(), false, true);
            else
                winner = checkWhoIsTheWinner(runResultHost.getRunResult(), runResultOpponent.getRunResult(), currentRunInformation.getDistance(), false, false);

            if (winner == 1) {
                //wygrana
                DatastoreProfile datastoreProfile = getDatastoreProfile(user);
                if (datastoreProfile == null)
                    return null;
                //dodajemy statystyki
                datastoreProfile.addWinRace();
                //dodajemy historie uzytkownika
                DatastoreProfileHistory profileHistory = new DatastoreProfileHistory();
                profileHistory.setParent(Key.create(DatastoreProfile.class, user.getEmail()));
                RunResultPiece lastResult = playerRunResult.getResults().get(playerRunResult.getResults().size()-1);
                float avgSpeed = countAvgSpeed((float)lastResult.getDistance(), (float)lastResult.getTime());
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
                //ofy().delete().entity(resultRunMap.get(playerKey));
                //TODO zmiana z gory na dol
                //removeFromCacheWholeRunInformation(p.getLogin());
                if(isHost)
                    removeFromCacheWholeRunInformationForPlayerAndBasicInfoForOpponent(p.getLogin(), currentRunInformation.getOpponentLogin());
                else
                    removeFromCacheWholeRunInformationForPlayerAndBasicInfoForOpponent(p.getLogin(), currentRunInformation.getHostLogin());
                ofy().delete().entity(runResultPlayer).now();
                return new RunResultPiece(-1, 1);
            }

            //syncCache.put(currentRunInformation.getHostLogin(), currentRunInformation);
            return makePrediction(currentRunInformation, isHost, forecast);
        }
    }

    /**
     * Wyciaganie z bazy profilu uzytkownika
     *
     * @return profil uzytkownika, null jesli uzytkownik nie istnieje
     */
    public DatastoreProfile getDatastoreProfile(User user) throws OAuthRequestException{
        Key key = Key.create(DatastoreProfile.class, user.getEmail());
        return (DatastoreProfile) ofy().load().key(key).now();
    }

    private CurrentRunInformation findRandomOpponent(DatastoreProfile datastoreProfile,Preferences preferences) {
        /*
        Na podstawie historii biegow uzytkownika uzyskanej za pomoca klucza oraz przy uwzglednieniu preferencji dystansu, dopieramy najbardziej odpowiadajacy bieg historyczny.
        Ustalamy dystans. Wypelniamy wstepnie informacje dotyczace biegu i zwracamy tak wypelniony obiekt.

        Tworzymy obiekt, przechowujace dane biegu.
        Ustawiamy, ze bieg dotyczy biegu z losowa osoba.
        Szukamy loginu uzytkownika.
            Brak to zwracamy null
        Ustawiamy pozostale dane dotyczace hosta.

        Szukamy przeciwnika.
        Znalezlismy:
        Nie znalezlismy:
         */
        CurrentRunInformation currentRunInformation = new CurrentRunInformation();
        currentRunInformation.setRunWithRandom(true);

        //currentRunInformation.setOwnerLogin(datastoreProfile.getLogin());
        currentRunInformation.setHostLogin(datastoreProfile.getLogin());
        currentRunInformation.setHostPreferences(preferences);

        //szukamy czy w ogole sa jakies wpisy w bazie na danym zakresie dystansow.
        int lowLimit, highLimit;
        if(preferences.getAspiration() <= preferences.getReservation()) {
            lowLimit = preferences.getAspiration();
            highLimit = preferences.getReservation();
        } else {
            lowLimit = preferences.getReservation();
            highLimit = preferences.getAspiration();
        }

        Query<DatastoreTotalHistory> query = ofy().load().type(DatastoreTotalHistory.class).order("totalDistance");
        query = query.filter("totalDistance >=",lowLimit).filter("totalDistance <=",highLimit);
        List<DatastoreTotalHistory> datastoreTotalHistoryList = query.limit(30).list();
        DatastoreTotalHistory opponentHistory;
        if(datastoreTotalHistoryList.isEmpty()) {
            opponentHistory = runGenerator(preferences, 10);
        } else {
            //jesli mamy przeciwnikow, to warto sprawdzic nasza charakterystyke biegu i mozliwie najlepiej dobrac przeciwnika (srednia dystans)
            //TODO
            //poki co zwracamy losowego
            Random generator = new Random();
            int i = generator.nextInt(datastoreTotalHistoryList.size());
            opponentHistory = datastoreTotalHistoryList.get(i);
        }

        currentRunInformation.setDistance(opponentHistory.getTotalDistance());
        currentRunInformation.setOpponentRunResult(opponentHistory.getRunResult());
        return currentRunInformation;
    }

    private RunResultPiece makePrediction(CurrentRunInformation currentRunInformation, boolean predictionForHost, int forecast) {
        /*
        Pobieramy rozmiar zebranych wynikow dla hosta i przeciwnika.
        Rozpatrujemy przewidywanie dla hosta (czyli analizujemy bieg jego przeciwnika)
            Jezeli mamy rozpatrywanie dla hosta to moze to byc bieg z obcym lub ze znajomym
            Bieg z obcym:
                //nasz oponent ma w pelni wypelniony bieg.
                Sprawdzamy czy jego ostatni element nie jest wczesniej niz nasz ostatni czas dla jakiego chcemy pozycje przeciwnika.
                Tak:
                    oznacza to nasza przegrana zwracamy ostatnie polozenie przeciwnika.

                Znajdujemy 2 kawalki biegu przeciwnika, 1 poprzedzajacy nasz aktualny czas, 2 bedacy wyprzedzeniem naszej predykcji wyliczamy polozenie pomiedzy.
                Iterujemy po wynikach przeciwnika az do wyniku wyprzedzajacego nasza predykcje.
                Jesli liczba kawalkow wieksza od 1:
                    Sprawdzamy czy wyszlismy z petli na break, jesli nie to znaczy ze przy naszej predykcji przeciwnik juz osiuagnal mete, wtedy rozpatrujemy bez uwzglednienia predykcji.
                Jesli 0 lub 1 kawalek:
                    zwracamy 0,0;
            Bieg ze znajomym:
        Rozpatrujemy przewidywanie dla niehosta (czyli analizujemy bieg hosta)
         */

        if(currentRunInformation.isRunWithRandom()) {
            int hostRunPiecesSize = currentRunInformation.getHostRunResult().getResults().size();
            int opponentRunPiecesSize = currentRunInformation.getOpponentRunResult().getResults().size();
            int lastHostTime = currentRunInformation.getHostRunResult().getResults().get(hostRunPiecesSize - 1).getTime();
            int predictionTime = lastHostTime + forecast;
            //sprawdzamy czy nie przegralismy juz w tym momencie
            //czy ostatni element biegu (bieg z obcym wiec bieg kompletny) nie jest wczesniej niz nasz ostatni element.
            if(currentRunInformation.getOpponentRunResult().getResults().get(opponentRunPiecesSize-1).getTime() <= lastHostTime)
                return currentRunInformation.getOpponentRunResult().getResults().get(opponentRunPiecesSize-1);
            int i;
            for(i = 0; i<opponentRunPiecesSize; ++i) {
                if(currentRunInformation.getOpponentRunResult().getResults().get(i).getTime() > predictionTime)
                    break;
            }
            if(i < 2)
                return new RunResultPiece(0,0);
            //jesli petla nie zakonczyla sie na breaku (czyli przy naszej predykcji przeciwnik juz osiagnal mete)
            if(i == opponentRunPiecesSize) {
                predictionTime = lastHostTime;
                //dekrementujemy, zeby nie wyjsc poza zakres
                i--;
            }
            //pomiedzy tymi kawalkami rozpatrujemy
            RunResultPiece piece1 = currentRunInformation.getOpponentRunResult().getResults().get(i-1);
            RunResultPiece piece2 = currentRunInformation.getOpponentRunResult().getResults().get(i);
            float t1, t2, t, d1, d2, x;
            t1 = (float) piece1.getTime();
            t2 = (float) piece2.getTime();
            t = (float) predictionTime;
            d1 = (float) piece1.getDistance();
            d2 = (float) piece2.getDistance();
            x = ((t-t1)*(d2-d1))/(t2-t1) + d1;
            return new RunResultPiece((int) x, (int) t);
        } else {
            //conajmniej 2 elementy u naszego przeciwnika
            //jesli pytanie dotyczy czasu ktory zawiera sie pomiedzy elementami przeciwnika
            //tak: wyliczamy na podstawie tych 2 elementow jego pozycje
            //nie (zapytanie dotyczy czasu jakiego przeciwnik jeszcze nie osiagnal):
                //na podstawie ostatnich 5 - 2 wpisow (w zaleznosci od tego ile ma) szacujemy jego pozycje
            /*
            Key hostKey = Key.create(RunResultDatastore.class, currentRunInformation.getHostRunResultId());
            Key opponentKey = Key.create(RunResultDatastore.class, currentRunInformation.getOpponentRunResultId());
            Map<Key<RunResultDatastore>, RunResultDatastore> resultRunMap = ofy().load().keys(hostKey, opponentKey);

            RunResult playerRunResult;
            RunResult opponentRunResult;
            if(predictionForHost) {
                if(resultRunMap.get(opponentKey) == null)
                    return new RunResultPiece(0,0);
                if(resultRunMap.get(opponentKey).getRunResult() == null)
                    return new RunResultPiece(0,0);
                playerRunResult = resultRunMap.get(hostKey).getRunResult();
                opponentRunResult = resultRunMap.get(opponentKey).getRunResult();
            } else {
                if(resultRunMap.get(hostKey) == null)
                    return new RunResultPiece(0,0);
                if(resultRunMap.get(hostKey).getRunResult() == null)
                    return new RunResultPiece(0,0);
                playerRunResult = resultRunMap.get(opponentKey).getRunResult();
                opponentRunResult = resultRunMap.get(hostKey).getRunResult();
            }
            */
            //TODO zamiana gory na dol
            Key hostKey = Key.create(RunResultDatastore.class, currentRunInformation.getHostRunResultId());
            Key opponentKey = Key.create(RunResultDatastore.class, currentRunInformation.getOpponentRunResultId());
            RunResultDatastore runResultHost = getFromCacheRunResultDatastore(currentRunInformation.getHostLogin());
            RunResultDatastore runResultOpponent = getFromCacheRunResultDatastore(currentRunInformation.getOpponentLogin());
            if(runResultHost == null) {
                runResultHost = (RunResultDatastore) ofy().load().key(hostKey).now();
                saveToCacheRunResultFromDatastore(currentRunInformation);
            }
            if(runResultOpponent == null) {
                runResultOpponent = (RunResultDatastore) ofy().load().key(opponentKey).now();
                saveToCacheRunResultFromDatastore(currentRunInformation);
            }
            RunResult playerRunResult;
            RunResult opponentRunResult;
            if(predictionForHost) {
                if(runResultOpponent == null)
                    return new RunResultPiece(0,0);
                if(runResultOpponent.getRunResult() == null)
                    return new RunResultPiece(0,0);
                playerRunResult = runResultHost.getRunResult();
                opponentRunResult = runResultOpponent.getRunResult();
            } else {
                if(runResultHost == null)
                    return new RunResultPiece(0,0);
                if(runResultHost.getRunResult() == null)
                    return new RunResultPiece(0,0);
                playerRunResult = runResultOpponent.getRunResult();
                opponentRunResult = runResultHost.getRunResult();
            }


            int playerRunResultSize = playerRunResult.getResults().size();
            int opponentRunResultSize = opponentRunResult.getResults().size();
            int lastPlayerTime = playerRunResult.getResults().get(playerRunResultSize-1).getTime();
            int predictionTime = lastPlayerTime + forecast;
            int i;
            for(i = 0; i<opponentRunResultSize; ++i) {
                if(opponentRunResult.getResults().get(i).getTime() > predictionTime)
                    break;
            }
            if(i < 2)
                return new RunResultPiece(0,0);
            if(i == opponentRunResultSize) {
                //jesli petla nie zakonczyla sie na breaku to szacowanie odbywa sie na podstawie 5 - 2 ostatnich elementow przeciwnika
                i--;
                //staramy sie siegnac od ostatniego elementu do 5 wstecz lub do mniej jesli elementow jest mniej
                int firstIndex = i - 5;
                if(firstIndex < 0)
                    firstIndex = 0;
                //wyliczamy srednia predkosc miedzy ostatnimi kawalkami
                RunResultPiece opponentLastPiece = opponentRunResult.getResults().get(i);
                RunResultPiece opponentFirstPiece = opponentRunResult.getResults().get(firstIndex);
                float dt = opponentLastPiece.getTime() - opponentFirstPiece.getTime();
                float ds = opponentLastPiece.getDistance() - opponentFirstPiece.getDistance();
                float aV = ds/dt;
                //zakladamy ze od ostatniego kawalka nasz przeciwnik porusza sie z wyiczona srednia predkoscia
                float dt2 = predictionTime - opponentLastPiece.getTime();
                float s = opponentLastPiece.getDistance() + aV*dt2;
                if(s > currentRunInformation.getDistance())
                    s = currentRunInformation.getDistance() - 1;
                return new RunResultPiece((int) s, predictionTime);
            }
            RunResultPiece piece1 = opponentRunResult.getResults().get(i-1);
            RunResultPiece piece2 = opponentRunResult.getResults().get(i);
            float t1, t2, t, d1, d2, x;
            t1 = (float) piece1.getTime();
            t2 = (float) piece2.getTime();
            t = (float) predictionTime;
            d1 = (float) piece1.getDistance();
            d2 = (float) piece2.getDistance();
            x = ((t-t1)*(d2-d1))/(t2-t1) + d1;
            return new RunResultPiece((int) x, (int) t);
        }
    }

    private DatastoreTotalHistory runGenerator(Preferences preferences, float avgSpeed) {
        /*
        Dobieramy dystans tak by byl losowy pomiedzy aspiracja a srodkiem miedzy aspiracja a rezerwacja
         */
        int lowLimit, highLimit, distance;
        boolean isHighAspiration;
        if(preferences.getAspiration() <= preferences.getReservation()) {
            lowLimit = preferences.getAspiration();
            highLimit = preferences.getReservation();
            isHighAspiration = false;
        } else {
            lowLimit = preferences.getReservation();
            highLimit = preferences.getAspiration();
            isHighAspiration = true;
        }

        int range = (highLimit - lowLimit)/2;
        Random generator = new Random();
        int i = generator.nextInt(range);
        if(isHighAspiration)
            distance = highLimit - i;
        else
            distance = lowLimit + i;

        DatastoreTotalHistory opponentHistory = new DatastoreTotalHistory();
        opponentHistory.setTotalDistance(distance);
        opponentHistory.setAverageSpeed(avgSpeed);

        int lastTime = (int) (distance/(avgSpeed*1000/3600));
        RunResultPiece firstPiece = new RunResultPiece(0,0);
        RunResultPiece lastPiece = new RunResultPiece(distance, lastTime);


        RunResult runResult = new RunResult();
        runResult.addResult(firstPiece);

        //trzeba wygenerowac losowo brakujace elementy pomiedzy firstPiece a lastPiece
        //generujemy liste z przebiegiem sekund od 5 do mniej niz lastTime skaczac losowo co 10 - 12 sekund

        List<Integer> timeList = new ArrayList<>();
        int time = 10;
        while (time < lastPiece.getTime()) {
            timeList.add(time);
            //dodajemy miedzy 10 a 12 sekund
            time += generator.nextInt(2) + 10;
        }

        //sprawdzamy rozmiar wygenerowanej listy + pierwszy wpis i ostatni i dzielimy caly dystans przez ta wartosc
        int distancePiece = distance/(2 + timeList.size());

        int distanceSum = 0;

        for (Integer aTimeList : timeList) {
            distanceSum += distancePiece;
            RunResultPiece runResultPiece = new RunResultPiece(distanceSum, aTimeList);
            runResult.addResult(runResultPiece);
        }

        runResult.addResult(lastPiece);
        opponentHistory.setRunResult(runResult);
        return opponentHistory;
    }

    private int checkWhoIsTheWinner(RunResult host, RunResult opponent, int totalDistance, boolean runWithRand, boolean playerIsHost){
        //zwraca 0 gdy brak zwyciescy, 1 jesli player wygral, 2 jesli opponent
        if(runWithRand){
            //sprawdzamy czy przekroczylismy dystans wyscigu
            if(host.getResults().get(host.getResults().size()-1).getDistance() >= totalDistance) {
                //sprawdzamy czy pierwsza probka u hosta ktora przekroczyla dystans miala lepszy czas
                //tak: host zwyciesca
                //nie: sprawdzamy czy ze stosunku pierwszej ktora przekroczyla wraz z ostatnia ktora tego nie dokonala jak wyliczymy czas dla osiagniecia zadanego dystansu to czy osiagnal zwyciestwo
                int i;
                for(i = 0; i < host.getResults().size(); ++i) {
                    if(host.getResults().get(i).getDistance() >= totalDistance)
                        break;
                }
                if(host.getResults().get(i).getTime() < opponent.getResults().get(opponent.getResults().size()-1).getTime())
                    return 1;

                RunResultPiece piece1 = host.getResults().get(i-1);
                RunResultPiece piece2 = host.getResults().get(i);
                float t1, t2, t, d1, d2, x;
                t1 = (float) piece1.getTime();
                t2 = (float) piece2.getTime();
                x = (float) totalDistance;
                d1 = (float) piece1.getDistance();
                d2 = (float) piece2.getDistance();
                t = ((x-d1)*(t2-t1))/(d2-d1) + t1;

                if(t < opponent.getResults().get(opponent.getResults().size()-1).getTime())
                    return 1;
                return 2;
            } else {
                //jesli nie przekroczylismy dystansu sprawdzamy czy nie przgralismy
                if(host.getResults().get(host.getResults().size()-1).getTime() > opponent.getResults().get(opponent.getResults().size()-1).getTime())
                    return 2;
                return 0;
            }

        }else {
            //sprawdzamy czy nasz przeciwnik przekroczyl linie mety
            //tak:
            //przegralismy
            //nie:
            //sprawdzamy czy przekroczylismy dystans calego wyscigu
            //przekroczylismy:
            //wygrana
            //nie przekroczylismy:
            //sprawdzamy czy ostatnie dane naszego przeciwnika sa swiezsze niz z przed 60 sekund
            //sa:
            //wyscig nie rozstrzygniety zwracamy 0
            //nie sa (ponad 60 sekund temu dawal znaki zycia):
            //traktujemy to jako wygrana
            if (playerIsHost) {
                if(opponent == null){
                    if(host.getResults().get(host.getResults().size() - 1).getTime() > 15)
                        return 1;
                    return 0;
                }
                if (opponent.getResults().get(opponent.getResults().size() - 1).getDistance() >= totalDistance)
                    return 2;
                if (host.getResults().get(host.getResults().size() - 1).getDistance() >= totalDistance) {
                    return 1;
                } else {
                    if ((host.getResults().get(host.getResults().size() - 1).getTime() - opponent.getResults().get(opponent.getResults().size() - 1).getTime()) > 60)
                        return 1;
                    return 0;
                }
            } else {
                if(host == null) {
                    if(opponent.getResults().get(opponent.getResults().size() - 1).getTime() > 15)
                        return 1;
                    return 0;
                }
                if (host.getResults().get(host.getResults().size() - 1).getDistance() >= totalDistance)
                    return 2;
                if (opponent.getResults().get(opponent.getResults().size() - 1).getDistance() >= totalDistance) {
                    return 1;
                } else {
                    if ((opponent.getResults().get(opponent.getResults().size() - 1).getTime() - host.getResults().get(host.getResults().size() - 1).getTime()) > 60)
                        return 1;
                    return 0;
                }
            }
        }
    }

    private CurrentRunInformation getNotFinishedRunForHost(String login) {
        Query<CurrentRunInformation> query = ofy().load().type(CurrentRunInformation.class).order("hostLogin");
        query = query.filter("hostLogin =",login);
        return query.first().now();
    }

    private CurrentRunInformation getNotFinishedRunForOpponent(String login) {
        Query<CurrentRunInformation> query = ofy().load().type(CurrentRunInformation.class).order("opponentLogin");
        query = query.filter("opponentLogin =",login);
        return query.first().now();
    }

    /**
     * Function to count average speed.
     * @param distance meters
     * @param time seconds
     * @return kilometers per hour
     */
    private float countAvgSpeed(float distance, float time) {
        return distance/time*(float)3600/(float)1000;
    }

    private int countDistance(Preferences p1, Preferences p2) {
        //Sprawdzamy czy jest czesc wspolna
        //brak zwracamy -1
        //zapisujemy ramy czesci wspolnej
        //jesli nasza aspiracja rezerwacja ma rozne zwroty to zwracamy srodek z czesci wspolnej
        //jesli te same to zwracamy albo jedna skrajna czesc lub druga w zaleznosci od zwrotu aspiracji
        int maxP1 = Math.max(p1.getAspiration(), p1.getReservation());
        int minP1 = Math.min(p1.getAspiration(), p1.getReservation());
        int maxP2 = Math.max(p2.getAspiration(), p2.getReservation());
        int minP2 = Math.min(p2.getAspiration(), p2.getReservation());
        int commonMax = Math.min(maxP1, maxP2);
        int commonMin = Math.max(minP1, minP2);
        if (commonMax < commonMin)
            return -1;
        //sprawdzanie kierunku aspiracji
        int apirationDirectionP1;
        if(p1.getAspiration() > p1.getReservation())
            apirationDirectionP1 = 1;
        else
            apirationDirectionP1 = -1;

        int apirationDirectionP2;
        if(p2.getAspiration() > p2.getReservation())
            apirationDirectionP2 = 1;
        else
            apirationDirectionP2 = -1;

        if(apirationDirectionP1 + apirationDirectionP2 == 0) {
            //oznacza rozne kierunki wiec zwracamy srednia z czesci wspolnej
            return (commonMax + commonMin)/2;
        } else if (apirationDirectionP1 == -1) {
            //aspiracja malejaca
            return commonMin;
        } else {
            return commonMax;

        }
    }

    private boolean checkIfLoginExist(String login) {
        Query<DatastoreProfile> query = ofy().load().type(DatastoreProfile.class).order("login");
        query = query.filter("login =",login);
        if(query.first().now() == null)
            return false;
        else
            return true;
    }

    private boolean cancelAllPairingProcesses(User user) throws OAuthRequestException {
        Profile ourProfile = signIn(user);
        if(ourProfile == null)
            return false;

        Query<RunMatcher> query = ofy().load().type(RunMatcher.class).order("opponentLogin");
        query = query.filter("opponentLogin =",ourProfile.getLogin());
        List<RunMatcher> runMatcherList = query.list();
        Query<RunMatcher> query2 = ofy().load().type(RunMatcher.class).order("hostLogin");
        query2 = query2.filter("hostLogin =",ourProfile.getLogin());
        List<RunMatcher> runMatcherList2 = query2.list();

        syncCache.delete("runMatch:".concat(ourProfile.getLogin()));

        boolean isSthDeleted = false;
        if(!runMatcherList.isEmpty()) {
            ofy().delete().entities(runMatcherList).now();
            isSthDeleted = true;
        }
        if(!runMatcherList2.isEmpty()){
            ofy().delete().entities(runMatcherList2).now();
            isSthDeleted = true;
        }
        return isSthDeleted;
    }

    private boolean cancelAllActiveUserRuns(User user) throws OAuthRequestException {
        //Anulowanie istniejacych wyscigow wraz z uwzglednieniem statystyk
        return cancelAllActiveUserRunForHost(user) && cancelAllActiveUserRunForOpponent(user);
    }

    private boolean cancelAllActiveUserRunForHost(User user) throws OAuthRequestException {
        DatastoreProfile p = getDatastoreProfile(user);
        if(p == null)
            return false;
        removeFromCacheWholeRunInformation(p.getLogin());
        CurrentRunInformation currentRunInformation = getNotFinishedRunForHost(p.getLogin());
        //syncCache.delete(p.getLogin());
        if(currentRunInformation == null)
            return true;
        removeFromCacheWholeRunInformation(currentRunInformation);
        if(currentRunInformation.isRunWithRandom()) {
            p.addLoseRace();
            ofy().delete().entity(currentRunInformation).now();
            ofy().save().entity(p).now();
            return true;
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
            return true;
        }
    }

    private boolean cancelAllActiveUserRunForOpponent(User user) throws OAuthRequestException {
        DatastoreProfile p = getDatastoreProfile(user);
        if(p == null)
            return false;
        removeFromCacheWholeRunInformation(p.getLogin());
        CurrentRunInformation currentRunInformation = getNotFinishedRunForOpponent(p.getLogin());
        //syncCache.delete("whoIsHostInMyRun:".concat(p.getLogin()));
        if(currentRunInformation == null)
            return true;
        removeFromCacheWholeRunInformation(currentRunInformation);
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
        return true;
    }

    private boolean cancelAllActiveUserRunProcesses(User user) throws OAuthRequestException {
        cancelAllPairingProcesses(user);
        cancelAllActiveUserRuns(user);
        return true;
    }

    private Profile getUserProfile(User user) throws OAuthRequestException {
        /*
        Opis dzialania:
        Sprawdzenie czy profil odpowiadajacy danemu uzytkownikowi znajduje sie w Memcache.
        Tak:
            Zwracamy profil.
        Nie:
            Sprawdzamy czy profil istnieje w bazie.
            Tak:
                Wsadzamy profil do Memcache pod kluczem 'emailUzytkownika'.
                Zwracamy Profil.
            Nie:
                Zwracamy null
         */
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

    private CurrentRunInformation getFromCacheCurrentRunInforamtion(String login) {
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

    private void saveToCacheCurrentRunInformation(CurrentRunInformation currentRunInformation, String login) {
        syncCache.put(login, new MemcacheRunInfo(currentRunInformation.isRunWithRandom(), currentRunInformation.getHostLogin()));
        if(currentRunInformation.isRunWithRandom())
            syncCache.put("RunWithRandom:".concat(currentRunInformation.getHostLogin()), currentRunInformation);
        else
            syncCache.put("RunWithFriend:".concat(currentRunInformation.getHostLogin()), currentRunInformation);
        //saveToCacheRunResultFromDatastore(currentRunInformation);
    }

    private void saveToCacheRunResult(RunResultDatastore runResultDatastore, String login) {
        syncCache.put("RunResult:".concat(login), runResultDatastore);
    }

    private void saveToCacheRunResultFromDatastore(CurrentRunInformation currentRunInformation) {
        RunResultDatastore runResultHost = getFromCacheRunResultDatastore(currentRunInformation.getHostLogin());
        RunResultDatastore runResultOpponent = getFromCacheRunResultDatastore(currentRunInformation.getOpponentLogin());
        if(runResultHost == null)
            saveToCacheRunResultsForHostFromDatastore(currentRunInformation);
        if(runResultOpponent == null)
            saveToCacheRunResultsForOpponentFromDatastore(currentRunInformation);
    }

    private boolean saveToCacheRunResultsForHostFromDatastore(CurrentRunInformation currentRunInformation) {
        //wyciagniecie z bazy i zapisanie dla hosta
        Key hostKey = Key.create(RunResultDatastore.class, currentRunInformation.getHostRunResultId());
        RunResultDatastore runResultDatastore = (RunResultDatastore) ofy().load().key(hostKey).now();
        if(runResultDatastore == null)
            return false;
        syncCache.put("RunResult:".concat(currentRunInformation.getHostLogin()), runResultDatastore);
        return true;
    }

    private boolean saveToCacheRunResultsForOpponentFromDatastore(CurrentRunInformation currentRunInformation) {
        //wyciagniecie z bazy i zapisanie dla przeciwnika
        Key opponentKey = Key.create(RunResultDatastore.class, currentRunInformation.getOpponentRunResultId());
        RunResultDatastore runResultDatastore = (RunResultDatastore) ofy().load().key(opponentKey).now();
        if(runResultDatastore == null)
            return false;
        syncCache.put("RunResult:".concat(currentRunInformation.getOpponentLogin()), runResultDatastore);
        return true;
    }

    private RunResultDatastore getFromCacheRunResultDatastore(String login) {
        return (RunResultDatastore) syncCache.get("RunResult:".concat(login));
    }

    private void removeFromCacheWholeRunInformation(String login) {
        syncCache.delete(login);
        syncCache.delete("RunWithRandom:".concat(login));
        syncCache.delete("RunWithFriend:".concat(login));
        syncCache.delete("RunResult:".concat(login));
    }

    private void removeFromCacheWholeRunInformationForPlayerAndBasicInfoForOpponent(String login, String oppLogin) {
        syncCache.delete(login);
        syncCache.delete(oppLogin);
        syncCache.delete("RunWithRandom:".concat(login));
        syncCache.delete("RunWithFriend:".concat(login));
        syncCache.delete("RunResult:".concat(login));
    }

    private void removeFromCacheWholeRunInformation(CurrentRunInformation currentRunInformation) {
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
}
