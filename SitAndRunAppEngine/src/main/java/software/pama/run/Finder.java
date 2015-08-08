package software.pama.run;

import com.googlecode.objectify.cmd.Query;
import software.pama.communication.RunPreferences;
import software.pama.datastore.run.CurrentRunInformation;
import software.pama.datastore.run.history.DatastoreTotalHistory;
import software.pama.datastore.users.DatastoreProfile;

import java.util.List;
import java.util.Random;

import static software.pama.datastore.OfyService.ofy;

/**
 * Created by Pawel on 2015-08-08.
 */
public class Finder {
    public static CurrentRunInformation findNotFinishedRunForHost(String login) {
        Query<CurrentRunInformation> query = ofy().load().type(CurrentRunInformation.class).order("hostLogin");
        query = query.filter("hostLogin =",login);
        return query.first().now();
    }

    public static CurrentRunInformation findNotFinishedRunForOpponent(String login) {
        Query<CurrentRunInformation> query = ofy().load().type(CurrentRunInformation.class).order("opponentLogin");
        query = query.filter("opponentLogin =",login);
        return query.first().now();
    }

    public static int findPerfectDistance(RunPreferences p1, RunPreferences p2) {
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

    public static CurrentRunInformation findRandomOpponent(DatastoreProfile datastoreProfile,RunPreferences runPreferences) {
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
        currentRunInformation.setHostRunPreferences(runPreferences);

        //szukamy czy w ogole sa jakies wpisy w bazie na danym zakresie dystansow.
        int lowLimit, highLimit;
        if(runPreferences.getAspiration() <= runPreferences.getReservation()) {
            lowLimit = runPreferences.getAspiration();
            highLimit = runPreferences.getReservation();
        } else {
            lowLimit = runPreferences.getReservation();
            highLimit = runPreferences.getAspiration();
        }

        Query<DatastoreTotalHistory> query = ofy().load().type(DatastoreTotalHistory.class).order("totalDistance");
        query = query.filter("totalDistance >=",lowLimit).filter("totalDistance <=",highLimit);
        List<DatastoreTotalHistory> datastoreTotalHistoryList = query.limit(30).list();
        DatastoreTotalHistory opponentHistory;
        if(datastoreTotalHistoryList.isEmpty()) {
            opponentHistory = RunGenerator.runGenerator(runPreferences, 10);
        } else {
            Random generator = new Random();
            int i = generator.nextInt(datastoreTotalHistoryList.size());
            opponentHistory = datastoreTotalHistoryList.get(i);
        }

        currentRunInformation.setDistance(opponentHistory.getTotalDistance());
        currentRunInformation.setOpponentRunResult(opponentHistory.getRunResult());
        return currentRunInformation;
    }
}
