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
import software.pama.run.datastore.CurrentRunInformation;
import software.pama.run.RunResult;
import software.pama.run.RunResultPiece;
import software.pama.run.friend.RunMatcher;
import software.pama.users.datastore.DatastoreProfile;
import software.pama.users.datastore.DatastoreProfileHistory;
import software.pama.users.datastore.DatastoreTotalHistory;
import software.pama.users.Profile;
import software.pama.utils.Constants;
import software.pama.utils.DateHelper;
import software.pama.utils.Preferences;
import software.pama.validation.Validator;
import software.pama.wrapped.WrappedBoolean;
import software.pama.wrapped.WrappedInteger;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created by Pawel on 2015-01-17.
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
     * @return null jesli uzytkownik jest calkiem nowy, profile jesli uzytkownik juz posiada konto
     */
    @ApiMethod(name = "signIn", path = "signIn")
    public Profile signIn(User user) throws OAuthRequestException{
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

    /**
     * Funkcja wykorzystywana do zakladania nowego konta.
     *
     * @param login nazwa uzytkownika jaka chcemy wykorzystywac (a-z, 0-9)
     * @return null jesli nazwa jest zajeta, profile jesli udalo sie zalozyc konto.
     *
     * @throws BadRequestException
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
     * @return true jesli blad
     */
    @ApiMethod(name = "deleteAccount", path = "deleteAccount")
    public WrappedBoolean deleteAccount(User user) throws OAuthRequestException{
        cancelCurrentRun(user);
        Key key = Key.create(DatastoreProfile.class, user.getEmail());
        ofy().delete().keys(ofy().load().ancestor(key).keys().list());
        ofy().delete().key(key).now();
        syncCache.delete(user.getEmail());
        return new WrappedBoolean(false);
    }

    /**
     * Funkcja wykorzystywana do zasygnalizowania, ze jestesmy gotowi do biegu z randomowa osoba.
     *
     * @param preferences - aspiracja oraz rezerwacja
     * @return 0 jesli oczekuje, > 0 start wyscigu liczba oznacza ilosc sekund do odliczenia, < 0 jesli blad
     */
    @ApiMethod(name = "startRunWithRandom", path = "startRunWithRandom")
    public WrappedInteger startRunWithRandom(User user, Preferences preferences) throws OAuthRequestException, BadRequestException{
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

        if(checkIfRunExistAsNotFinished(datastoreProfile.getLogin()))
            cancelCurrentRun(user);

        CurrentRunInformation runInfo = findRandomOpponent(datastoreProfile, preferences);

        runInfo.setLastDatastoreSavedTime(new Date());
        ofy().save().entity(runInfo).now();
        syncCache.put(runInfo.getHostLogin(), runInfo);

        Random generator = new Random();
        int i = generator.nextInt(30) + 30;
        return new WrappedInteger(i);
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
        //TODO anulowanie wszystkich naszych biegow z uwzglednieniem dodania statystyk (przeanalizowac funkcje usuwajaca)
        cancelRun(user);
        //TODO zadbac o usuniecie wpisow parowania ktore do nas naleza
        RunMatcher runMatcher = new RunMatcher(ourProfile.getLogin(), friendsLogin, preferences);
        ofy().save().entity(runMatcher).now();
        syncCache.put("runMatch:".concat(ourProfile.getLogin()), runMatcher);
        return new WrappedBoolean(true);
    }

    /**
     * Funkcja sluzaca sprawdzeniu czy mamy sparowany wyscig.
     *
     * @param preferences - aspiration and reservation
     * @return -1 - blad, 0 - jesli oczekujemy, >0 - czas do startu wyscigu
     */
    @ApiMethod(name = "startRunWithFriend", path = "startRunWithFriend")
    public WrappedInteger startRunWithFriend(User user, Preferences preferences) throws OAuthRequestException{
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
        if(!Validator.isPreferencesCorrect(preferences))
            return new WrappedInteger(-1);
        Profile ourProfile = signIn(user);
        if(ourProfile == null)
            return new WrappedInteger(-1);
        //TODO anulowanie wszystkich trwajacych biegow
        RunMatcher runMatcher = (RunMatcher) syncCache.get("runMatch:".concat(ourProfile.getLogin()));
        if(runMatcher == null) {
            Query<RunMatcher> query = ofy().load().type(RunMatcher.class).order("hostLogin");
            query = query.filter("hostLogin =",ourProfile.getLogin());
            runMatcher = query.first().now();
            if(runMatcher == null)
                return new WrappedInteger(-1);
            syncCache.put("runMatch:".concat(ourProfile.getLogin()), runMatcher);
        }
        if(!runMatcher.isComplete())
            return new WrappedInteger(0);
        CurrentRunInformation currentRunInformation = new CurrentRunInformation();
        currentRunInformation.setHostLogin(runMatcher.getHostLogin());
        currentRunInformation.setOpponentLogin(runMatcher.getOpponentLogin());
        currentRunInformation.setRunWithRandom(false);
        currentRunInformation.setDistance(runMatcher.getDistance());
        currentRunInformation.setLastDatastoreSavedTime(new Date());
        Date acceptByOpponentDate = runMatcher.getAcceptByOpponentDate();
        ofy().delete().entity(runMatcher).now();
        ofy().save().entity(currentRunInformation).now();
        syncCache.delete("runMatch:".concat(ourProfile.getLogin()));
        syncCache.put(ourProfile.getLogin(), currentRunInformation);
        int d = (int) DateHelper.getDateDiff(acceptByOpponentDate, new Date(), TimeUnit.SECONDS);
        return new WrappedInteger(40 - d);
    }

    /**
     * Funkcja sluzaca sprawdzeniu czy mamy sparowany wyscig.
     *
     * @param preferences - aspiration and reservation
     * @return -1 - blad, >0 - ustalony dystans
     */
    @ApiMethod(name = "joinRunWithFriend", path = "joinRunWithFriend")
    public WrappedInteger joinRunWithFriend(User user, Preferences preferences) throws OAuthRequestException{
        //Sprawdzenie poprawnosci parametrow
        //wyciagamy nasz profil z bazy
        //wyciagamy z bazy wpis gdzie widnieje nasz login jako opponent
        //probujemy wyciagnac z bazy - jesli brak zwracamy blad
        //jesli jest porownujemy preferencje i dobieramy dystans
        //uzupelniamy wyciagniety wpis, po czym zapisujemy go do bazy i do memcache pod "runwithfriend:login"
        //zwracamy ustalony dystans
        if(!Validator.isPreferencesCorrect(preferences))
            return new WrappedInteger(-1);
        Profile ourProfile = signIn(user);
        if(ourProfile == null)
            return new WrappedInteger(-1);
        Query<RunMatcher> query = ofy().load().type(RunMatcher.class).order("opponentLogin");
        query = query.filter("opponentLogin =",ourProfile.getLogin());
        List<RunMatcher> runMatcherList = query.list();
        if(runMatcherList == null)
            return new WrappedInteger(-2);
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
        //To nigdy nie powinno miec miejsce, napisane, zeby kompilator nie mial watpliwosci
        if(newestRun == null)
            return new WrappedInteger(-3);
        runMatcherList.remove(newestRun);
        ofy().delete().entities(runMatcherList).now();
        int distance = countDistance(preferences, newestRun.getHostPreferences());
        if(distance < 0)
            return new WrappedInteger(-4);
        newestRun.setDistance(distance);
        newestRun.setAcceptByOpponentDate(new Date());
        newestRun.setComplete(true);
        ofy().save().entity(newestRun).now();
        syncCache.put("runMatch:".concat(newestRun.getHostLogin()), newestRun);
        syncCache.put("whoIsHostInMyRun:".concat(ourProfile.getLogin()), newestRun.getHostLogin());
        return new WrappedInteger(distance);
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

        CurrentRunInformation currentRunInformation = (CurrentRunInformation) syncCache.get(ourProfile.getLogin());
        if(currentRunInformation == null) {
            Query<CurrentRunInformation> query = ofy().load().type(CurrentRunInformation.class).order("opponentLogin");
            query = query.filter("opponentLogin =",ourProfile.getLogin());
            currentRunInformation = query.first().now();
            if(currentRunInformation == null) {
                //sprawdzamy czy istnieje nasz RunMatcher - probujemy wyciagnac

                RunMatcher runMatcher = (RunMatcher) syncCache.get("runMatch:".concat(ourProfile.getLogin()));
                if(runMatcher == null) {
                    Query<RunMatcher> query2 = ofy().load().type(RunMatcher.class).order("opponentLogin");
                    query2 = query2.filter("opponentLogin =",ourProfile.getLogin());
                    runMatcher = query2.first().now();
                    if(runMatcher == null)
                        return new WrappedBoolean(false);
                }
                if(DateHelper.getDateDiff(runMatcher.getAcceptByOpponentDate(), new Date(), TimeUnit.SECONDS) > 20) {
                    //host sie rozlaczyl
                    //TODO czyscimy caly bieg
                    return new WrappedBoolean(false);
                }
                return new WrappedBoolean(false);
            }
        }

        if(currentRunInformation.getStarted())
            return new WrappedBoolean(false);
        RunMatcher runMatcher = (RunMatcher) syncCache.get("runMatch:".concat(ourProfile.getLogin()));
        if(runMatcher == null) {
            Query<RunMatcher> query = ofy().load().type(RunMatcher.class).order("opponentLogin");
            query = query.filter("opponentLogin =",ourProfile.getLogin());
            runMatcher = query.first().now();
            if(runMatcher == null) {
                currentRunInformation.setStarted(true);
                ofy().save().entity(currentRunInformation);
                return new WrappedBoolean(true);
            }
        }
        return new WrappedBoolean(false);
    }

    /**
     * Funkcja wykorzystywana do anulowania udzialu w biegu z uwzglednieniem statystyk
     * @return 0 jesli anulowanie przebieglo pomyslnie, <0 jesli nie bylo czego anulowac lub blad
     */
    @ApiMethod(name = "cancelRun", path = "cancelRun")
    public WrappedInteger cancelRun(User user) throws OAuthRequestException{
        if(cancelCurrentRun(user).getResult() == 0) {
            DatastoreProfile datastoreProfile = getDatastoreProfile(user);
            datastoreProfile.addLoseRace();
            ofy().save().entity(datastoreProfile);
            syncCache.put(user.getEmail(), new Profile(datastoreProfile));
            return new WrappedInteger(0);
        }
        return new WrappedInteger(-1);
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
                        //TODO
                    Nasz:
                        //TODO
                Z randomem:
                    Wyciagniecie wpisu uaktualnienie rezultatow i daty.
                    Zapisanie do memcache.
        Istnieje:
            Sprawdzamy czy bieg z prawdziwym i czy to nasz bieg.
            Z prawdziwym:
                Nie nasz:
                    Wyciagamy inny wpis.
                    //TODO
                Nasz:
                    //TODO
            Z randomem:
                Uaktualnienie rezultatow oraz sprawdzenie daty zapisu.
                Dawno:
                    Zapis do bazu.
        Na podstawie rezultatow przeciwnika i informacji o czasie przewidywania obliczamy pozycje przeciwnika.
        Zwracamy pozycje przeciwnika.
         */
        if(forecast < 0 || forecast > 30 || !Validator.isRunResultCorrect(runResult))
            throw new BadRequestException("Bad parameter format");

        Profile p = signIn(user);
        if(p == null)
            return null;

        CurrentRunInformation currentRunInformation = (CurrentRunInformation) syncCache.get(p.getLogin());
        if(currentRunInformation == null) {
            currentRunInformation = getNotFinishedRun(p.getLogin());
            if(currentRunInformation == null) {
                String login= (String) syncCache.get("whoIsHostInMyRun:".concat(p.getLogin()));
                currentRunInformation = (CurrentRunInformation) syncCache.get(login);
                if(currentRunInformation == null) {
                    currentRunInformation = getNotFinishedRun(login);
                    if(currentRunInformation == null)
                        return null;
                }
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
                RunResultPiece parameterFirstRunResultPiece = runResult.getResults().get(runResult.getResults().size()-1);
                if(hostLastRunResultPiece.getTime() >= parameterFirstRunResultPiece.getTime())
                    throw new BadRequestException("Run results are redundant");
                if(hostLastRunResultPiece.getDistance() > parameterFirstRunResultPiece.getDistance())
                    throw new BadRequestException("Run results are wrong");
                hostRunResult.addResults(runResult.getResults());
            }
            currentRunInformation.setHostRunResult(hostRunResult);

            int winner = checkWhoIsTheWinner(hostRunResult, currentRunInformation.getOpponentRunResult(), currentRunInformation.getDistance(), true);

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

                cancelCurrentRun(user);
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

                cancelCurrentRun(user);
                return new RunResultPiece(-1, 1);
            }

            syncCache.put(p.getLogin(), currentRunInformation);
            if(new Date().getTime() - currentRunInformation.getLastDatastoreSavedTime().getTime() > 1000*30) {
                currentRunInformation.setLastDatastoreSavedTime(new Date());
                ofy().save().entity(currentRunInformation).now();
            }

            return makePrediction(currentRunInformation, true, forecast);
        } else {
            //TODO bezpiecznie watkowo
            //sprawdzenie czy host
        }
        return new RunResultPiece(1, 0);
    }

    /**
     * Wyciaganie z bazy profilu uzytkownika
     *
     * @return profil uzytkownika, null jesli uzytkownik nie istnieje
     */
    public DatastoreProfile getDatastoreProfile(User user) throws OAuthRequestException{
        /*
        Opis dzialania:
            Wyciagamy profil z bazy na podstawie adresu email.
            Jesli profil nie istnieje w bazie, proba wyciagniecia zwraca null.
            Zwracamy wynik proby wyciagniecia profilu z bazy.
         */
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
        //TODO rozwinac opis, zeby zawieral cala metodologie
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
        int hostRunPiecesSize = currentRunInformation.getHostRunResult().getResults().size();
        int opponentRunPiecesSize = currentRunInformation.getOpponentRunResult().getResults().size();

        if(predictionForHost) {
            if(currentRunInformation.isRunWithRandom()) {
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
            }
        } else {
        }

        return new RunResultPiece();
    }

    private DatastoreTotalHistory runGenerator(Preferences preferences, float avgSpeed) {
        //TODO opis szczegolowy
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

    private int checkWhoIsTheWinner(RunResult host, RunResult opponent, int totalDistance, boolean runWithRand){
        //TODO opis
        //zwraca 0 gdy brak zwyciescy, 1 jesli host, 2 jesli opponent
        if(runWithRand){
            //TODO zwraca 2 na start???
/*
            //sprawdzamy czy przekroczylismy dystans wyscigu
            if(host.getResults().get(host.getResults().size()-1).getDistance() > totalDistance) {
                //sprawdzamy czy pierwsza probka u hosta ktora przekroczyla dystans miala lepszy czas
                //tak: host zwyciesca
                //nie: sprawdzamy czy ze stosunku pierwszej ktora przekroczyla wraz z ostatnia ktora tego nie dokonala jak wyliczymy czas dla osiagniecia zadanego dystansu to czy osiagnal zwyciestwo
                int i;
                for(i = 0; i < host.getResults().size(); ++i) {
                    if(host.getResults().get(i).getDistance() > totalDistance)
                        break;
                }
                if(host.getResults().get(i).getTime() < opponent.getResults().get(host.getResults().size()-1).getTime())
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

                if(t < opponent.getResults().get(host.getResults().size()-1).getTime())
                    return 1;
                return 2;
            } else {
                //jesli nie przekroczylismy dystansu sprawdzamy czy nie przgralismy
                if(host.getResults().get(host.getResults().size()-1).getTime() > opponent.getResults().get(host.getResults().size()-1).getTime())
                    return 2;
                return 0;
            }
*/
        }else {

        }
        return 0;
    }

    private boolean checkIfRunExistAsNotFinished(String login) {
        return getNotFinishedRun(login) != null;
    }

    private CurrentRunInformation getNotFinishedRun(String login) {
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

    private WrappedInteger cancelCurrentRun(User user) throws OAuthRequestException{
        //w przypadku wywolania canel w pierwszej kolejnosci sprawdzamy czy mamy profil odpowiadajacy mailowi
        //jesli tak to dalej jesli nie to blad

        //sprawdzamy czy nasz login bierze udzial w matchowaniu czy moze juz biegnie, kazdy przypadek musimy rozpatrzec pod kontem takze jak powinna zareagowac druga strona
        //jest w trakcie matchowania
        //jako host
        //usuwa wpis z memcache - gosc nawet jezeli wprowadzil sobie cos w trakcie to po odliczeniu na pierwszej probie przekazania wyniku biegu czyli zaraz po odliczeniu napotka na blad odniesienia, gdy wpisu docelowego nie bedzie ani w bazie ani w memcache, wiec oznaczac to bedzie rozlaczenie hosta
        //jako gosc
        //nie wiem czy da sie anulowac w trakcie matchowania, jesli matchowanie sie odbywa to nie mozliwym jest wywolac od razu funkcje anulowania
        //anulowanie po wykryciu biegu sprowadza sie jedynie do usuniecia lub modyfikacji informacji o naszym odejsciu w memcache

        //jesli cancel bedzie wykryty w momencie gdy nbiby juz jest bieg ale nie splynely zadne wyniki (czyli w trakcie odliczania)
        //wtedy ustawiamy flage o rezygnacji, wtedy druga strona zajmuje sie usunieciem wszystkiego

        //jesli biegnie
        //czy z kims
        //czy jest hostem
        //ustawiamy w memcahce i w bazie flage o rezygnacji, wtedy druga strona zajmuje sie zakonczeniem wyscigu
        //jest gosciem
        //ustawiamy w memcache i w bazie flage o rezygnacji, wtedy druga strona zajmuje sie zakonczeniem wyscigu

        //biegnie z randomem
        //nic sie nie dzieje, chyba ze jestesmy w trakcie biegu wtedy dochodzi nam bieg do statystyk ale nie wygrana

        /*
        Opis dzialania:
        Anulowanie wyscigu randomowego:
        Wyciagniecie z bazy loginu uzytkownika.
        Sprawdzenie czy wystepuje wpis w memcache (w sposob delikatny, gdyz moze to byc bieg z kims).
        Tak:
            Przeanalizowanie wpisu czy jest to bieg z randomowa osoba.
            Tak:
                Usuniecie wpisu.
                Zaznaczenie flagi ze jest to bieg z randomem (przyda sie przy wyciaganiu danych z bazy w sposob niekonieczne ostrozny watkowo)
            Nie:
                //TODO
        Wyciagamy wpis z bazy. (metoda zalezna od flagi czy biegniemy z randomem)
        Przeanalizowanie wpisu czy jest to bieg z randomowa osoba. (moglo nie dojsc do analizy wczesniej, gdyz memcache mogl zostac wymieciony)
            Tak:
                Usuniecie wpisu.
                Wyciagamy z bazy nasz profil.
                Zwiekszamy o 1 ilosc wyscigow w jakich wzielismy udzial.
                Zapisujemy do bazy.
                Uaktualniamy memcache.
                zwracamy true
            Nie:
                //TODO
         */
        Profile p = signIn(user);
        if(p == null)
            return new WrappedInteger(-1);

        CurrentRunInformation currentRunInformation = getNotFinishedRun(p.getLogin());
        if(currentRunInformation == null) {
            //dla pewnosci usuwamy wpis z cache (nigdy i tak nie powinno go byc)
            syncCache.delete(p.getLogin());
            return new WrappedInteger(-2);
        }

        if(currentRunInformation.isRunWithRandom()) {
            ofy().delete().entity(currentRunInformation).now();
            syncCache.delete(p.getLogin());
            return new WrappedInteger(0);
        } else {
            if(currentRunInformation.getHostLogin() == p.getLogin()) {

            } else {

            }
        }
        return new WrappedInteger(0);
    }

    private int countDistance(Preferences p1, Preferences p2) {
        //TODO
        return 10;
    }

    private boolean checkIfLoginExist(String login) {
        Query<DatastoreProfile> query = ofy().load().type(DatastoreProfile.class).order("login");
        query = query.filter("login =",login);
        if(query.first().now() == null)
            return false;
        else
            return true;
    }
}
