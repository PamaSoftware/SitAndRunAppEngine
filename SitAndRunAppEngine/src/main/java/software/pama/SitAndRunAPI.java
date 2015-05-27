package software.pama;


import static software.pama.OfyService.ofy;

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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

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
        if(!isLoginCorrect(login))
            throw new BadRequestException("Bad parameter format");
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
        //TODO transakcja usuniecia calej historii danego uzytkownika wraz z uzytkownikiem
        Key key = Key.create(DatastoreProfile.class, user.getEmail());
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
        if(!isPreferencesCorrect(preferences))
            throw new BadRequestException("Bad parameter format");

        Key key = Key.create(DatastoreProfile.class, user.getEmail());
        DatastoreProfile datastoreProfile = (DatastoreProfile) ofy().load().key(key).now();
        if(datastoreProfile == null)
            return null;

        Query<MemcacheRunInfo> query = ofy().load().type(MemcacheRunInfo.class).order("ownerLogin");
        query = query.filter("ownerLogin =",datastoreProfile.getLogin());
        if(query.first().now() != null){
            cancelRun(user);
        }

        MemcacheRunInfo runInfo = findRandomOpponent(datastoreProfile, preferences);
        if(runInfo == null)
            return new WrappedInteger(-1);

        //zapisujemy runInfo do bazy oraz do memcache
        //zwracamy date startu

        runInfo.setLastDatastoreSavedTime(new Date());
        runInfo.setDuringRace(true);
        runInfo.setHostRunResult(new RunResult());
        ofy().save().entity(runInfo).now();
        syncCache.put(runInfo.getOwnerLogin(), runInfo);

        Random generator = new Random();
        int i = generator.nextInt(30) + 30;
        return new WrappedInteger(i);

    }

    /**
     * Funkcja wykorzystywana do zasygnalizowania, ze jestesmy gotowi do biegu ze znajomym.
     *
     * @param preferences - aspiration and reservation
     * @param gcmID - gcm number
     * @param friendsLogin - if given we try to join to friend which should be host, if null we start being host
     * @return 0 if waiting, > 0 if start and then number is the amount of second to wait before start gathering information, < 0 if error
     */
    @ApiMethod(name = "startRunWithFriend", path = "startRunWithFriend")
    public WrappedInteger startRunWithFriend(User user, Preferences preferences, @Named("gcmID") String gcmID, @Named("friendsLogin") String friendsLogin) throws OAuthRequestException{
        //Sprawdzamy poprawnosc danych
        //Upewniamy sie ze profil istnieje, w tym celu sprawdzamy czy mamy w memcache, jesli nie to staramy sie wyciagnac z bazy
        //jezeli nie ma profilu zwracamy blad -1

        //jezeli mamy do czynienia z hostem
        //jezeli nie istnieje to stworzenie wpisu w Memcache oznaczajacego oczekiwanie
        //jezeli istnieje to sprawdzenie czy mamy kandydata do biegu
            //jesli nie ma to uaktualniamy date tylko
            //jesli mamy to tworzymy juz w pelni prawidlowy wpis w memcache oraz w bazie danych i zwracamy ilosc sekund do odliczenia wyliczajac to z daty wzietej z wpisu (gosc podmienil date na date startu) i na podstawie roznicy z aktualna data zwracamy ilosc sekund

        //jezeli mamy do czynienia z gosciem
        //sprawdzamy czy jest w memcache wpis jezeli nie ma odczekujemy 5 sekund i sprawdzamy ponownie jesli nie ma to zwracamy blad
        //jesli jest to porownujemy z naszymi preferencjami
            //jezeli sie zgadzaja to tworzymy sobie wlasny wpis w memcache dotyczacy biegu po czym zapisujemy do wpisu hosta informacje o naszej checi  uczestnictwa wraz z czasem startu oraz dystansem wynikajacym z naszych wspolnych preferencji, po czym zwracamy liczbe sekund do startu
            //jezeli preferencje nie maja wspolnej czesci to zwracamy blad sygnalizujacy o tym fakcie, oraz wprowadzamy do wpisu informacje o braku wspolnych preferncji
        return new WrappedInteger(0);
    }

    /**
     * Funkcja wykorzystywana do anulowania udzialu w biegu
     * @return true jesli anulowanie przebieglo pomyslnie
     */
    @ApiMethod(name = "cancelRun", path = "cancelRun")
    public WrappedBoolean cancelRun(User user) throws OAuthRequestException{
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

        //KWESTIA BEZPIECZENSTWA WATKOWEGO
        Profile p = signIn(user);
        if(p == null)
            return new WrappedBoolean(false);
        Query<MemcacheRunInfo> query = ofy().load().type(MemcacheRunInfo.class).order("ownerLogin");
        query = query.filter("ownerLogin =",p.getLogin());
        MemcacheRunInfo memcacheRunInfo = query.first().now();
        if(memcacheRunInfo == null){
            syncCache.delete(p.getLogin());
            return new WrappedBoolean(true);
        }
        //jesli jednak jest to sprawdzamy czy bieg z randomem oraz czy juz sie rozpoczal
        if(memcacheRunInfo.isRunWithRandom) {
            //jesli z randomem to wiadomo ze jestesmy tez hostem
            ofy().delete().entity(memcacheRunInfo).now();
            syncCache.delete(p.getLogin());
            //TODO jesli w trakcie biegu to zmieniaja nam sie statystyki
            return new WrappedBoolean(true);
        } else {
            if(memcacheRunInfo.getHostLogin() == p.getLogin()) {

            } else {

            }
        }
        return new WrappedBoolean(false);
    }

    /**
     * Funkcja sluzaca do informowania o aktualnym postepie w biegu
     * @param runResult - lista wynikow jakie zapisalismy
     * @param forecast - czas w sekundach na ile w przod ma wybiec z obliczeniem serwer i zwrocic pozycje przeciwnika, jezeli aplikacja widzi ze regularnie serwer odpowiada srednio po 3 sekundach, powinna wpisac 3 sekundy, aby w momencie otrzymania informacji byla ona mozliwie najbardziej aktualna
     *
     * @return dystans przeciwnika jaki mial po odpowiadajacym czasie
     */
    @ApiMethod(name = "currentRunState", path = "currentRunState")
    public RunResultPiece currentRunState(User user, RunResult runResult, @Named("forecast") int forecast) throws OAuthRequestException{
        //TODO wpisywanie danych do memcache, oraz wyliczenie w jakim miejscu przeciwnik byl w danym czasie, najlepiej z zasymulowaniem paru sekund do przodu i zwroceniu czasu
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

        //TODO sprawdzenie poprawnosci danych

        Profile p = signIn(user);
        if(p == null)
            return null;

        MemcacheRunInfo memcacheRunInfo = (MemcacheRunInfo) syncCache.get(p.getLogin());
        if(memcacheRunInfo == null) {
            Query<MemcacheRunInfo> query = ofy().load().type(MemcacheRunInfo.class).order("ownerLogin");
            query = query.filter("ownerLogin =",p.getLogin());
            memcacheRunInfo = query.first().now();
            if(memcacheRunInfo == null)
                return null;
        }

        if(memcacheRunInfo.isRunWithRandom){
            //uaktualnienie wpisu
            //zapis do memcache oraz jesli dawno to do bazy
            //przewidzenie pozycji przeciwnika
            RunResult hostRunResult = memcacheRunInfo.getHostRunResult();
            if(hostRunResult == null)
                hostRunResult = new RunResult(runResult.getResults());
            else
                hostRunResult.addResults(runResult.getResults());
            memcacheRunInfo.setHostRunResult(hostRunResult);
            syncCache.put(p.getLogin(), memcacheRunInfo);
            if(new Date().getTime() - memcacheRunInfo.getLastDatastoreSavedTime().getTime() > 1000*30) {
                memcacheRunInfo.setLastDatastoreSavedTime(new Date());
                ofy().save().entity(memcacheRunInfo).now();
            }
            return makePrediction(memcacheRunInfo, true, forecast);
        } else {
            //TODO bezpiecznie watkowo
            //sprawdzenie czy host
        }
        return new RunResultPiece(1, 0);
    }

    private MemcacheRunInfo findRandomOpponent(DatastoreProfile datastoreProfile,Preferences preferences) {
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
        MemcacheRunInfo memcacheRunInfo = new MemcacheRunInfo();
        memcacheRunInfo.setRunWithRandom(true);

        memcacheRunInfo.setOwnerLogin(datastoreProfile.getLogin());
        memcacheRunInfo.setHostLogin(datastoreProfile.getLogin());
        memcacheRunInfo.setHostPreferences(preferences);

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

        memcacheRunInfo.setDistance(opponentHistory.getTotalDistance());
        memcacheRunInfo.setOpponentRunResult(opponentHistory.getRunResult());
        return memcacheRunInfo;
    }

    private boolean isPreferencesCorrect(Preferences preferences) {
        //TODO
        return true;
    }

    private boolean isLoginCorrect(String login) {
        /*
        Opis dzialania:
        Sprawdzenie czy login sklada sie tylko i wylacznie z: a-z lub 0-9
        Tak:
            Zwracamy true
        Nie:
            Zwracamy false
         */
        //TODO
        return true;
    }

    private boolean isGcmIDCorrect(String gcmID) {
        //TODO
        return true;
    }

    private RunResultPiece makePrediction(MemcacheRunInfo memcacheRunInfo, boolean predictionForHost, int forecast) {
        //prediction is based on 5 last infoPieces

        if(predictionForHost) {
            int hostRunPiecesSize = memcacheRunInfo.getHostRunResult().getResults().size();
            int opponentRunPiecesSize = memcacheRunInfo.getOpponentRunResult().getResults().size();
            int lastHostTime = memcacheRunInfo.getHostRunResult().getResults().get(hostRunPiecesSize - 1).getTime();
            int i;
            for(i = 0; i<opponentRunPiecesSize; ++i) {
                    if(memcacheRunInfo.getOpponentRunResult().getResults().get(i).getTime() > lastHostTime+forecast)
                        break;
            }
            i--;
            if(i < 6) {
                if(memcacheRunInfo.getOpponentRunResult().getResults().size() > 10)
                    return memcacheRunInfo.getOpponentRunResult().getResults().get(i);
                return memcacheRunInfo.getHostRunResult().getResults().get(hostRunPiecesSize - 1);
            }

            int distance1, distance2, time1, time2;
            distance1 = memcacheRunInfo.getOpponentRunResult().getResults().get(i).getDistance() - memcacheRunInfo.getOpponentRunResult().getResults().get(i-5).getDistance();
            time1 = memcacheRunInfo.getOpponentRunResult().getResults().get(i).getTime() - memcacheRunInfo.getOpponentRunResult().getResults().get(i-5).getTime();
            time2 = lastHostTime+forecast - memcacheRunInfo.getOpponentRunResult().getResults().get(i).getTime();
            if(time1 != 0)
                distance2 = distance1*time2/time1;
            else
                distance2 = memcacheRunInfo.getOpponentRunResult().getResults().get(i).getDistance();
            return new RunResultPiece(distance2, lastHostTime+forecast);
            //TODO JAKIES PRZEJEBANE PROGNOZY!!!!
        } else {

        }

        return new RunResultPiece();
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
}
