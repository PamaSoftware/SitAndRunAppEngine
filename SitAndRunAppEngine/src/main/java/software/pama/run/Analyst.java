package software.pama.run;

import com.google.appengine.api.memcache.MemcacheService;
import com.googlecode.objectify.Key;
import software.pama.datastore.run.CurrentRunInformation;
import software.pama.datastore.run.RunResultDatastore;

import static software.pama.datastore.OfyService.ofy;

/**
 * Created by Pawel on 2015-08-08.
 */
public class Analyst {
    public static RunResultPiece makePrediction(CurrentRunInformation currentRunInformation, boolean predictionForHost, int forecast, MemcacheService syncCache) {
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

            Key hostKey = Key.create(RunResultDatastore.class, currentRunInformation.getHostRunResultId());
            Key opponentKey = Key.create(RunResultDatastore.class, currentRunInformation.getOpponentRunResultId());
            RunResultDatastore runResultHost = CacheOrganizer.getFromCacheRunResultDatastore(currentRunInformation.getHostLogin(), syncCache);
            RunResultDatastore runResultOpponent = CacheOrganizer.getFromCacheRunResultDatastore(currentRunInformation.getOpponentLogin(), syncCache);
            if(runResultHost == null) {
                runResultHost = (RunResultDatastore) ofy().load().key(hostKey).now();
                CacheOrganizer.saveToCacheRunResultFromDatastore(currentRunInformation, syncCache);
            }
            if(runResultOpponent == null) {
                runResultOpponent = (RunResultDatastore) ofy().load().key(opponentKey).now();
                CacheOrganizer.saveToCacheRunResultFromDatastore(currentRunInformation, syncCache);
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

    public static int checkWhoIsTheWinner(RunResult host, RunResult opponent, int totalDistance, boolean runWithRand, boolean playerIsHost){
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

    public static float countAvgSpeed(float distance, float time) {
        return distance/time*(float)3600/(float)1000;
    }
}
